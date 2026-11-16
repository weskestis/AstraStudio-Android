#include "RenderCore.h"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace astra {
namespace {
constexpr float kCeiling = fx::LookaheadLimiter::ceiling;
constexpr int kCommandsPerCallback = 256;
constexpr int kEventsPerCallback = 512;
}

RenderCore::RenderCore() noexcept {
    for (std::size_t i = 0; i < sine_.size(); ++i) {
        sine_[i] = static_cast<float>(std::sin(2.0 * std::numbers::pi * static_cast<double>(i) / 2048.0));
    }
    sequence_.bind(this,sequenceNote,sequenceAutomation);
    for(int i=0;i<kTrackCount;++i){publishedClips_[i]=-1;publishedQueued_[i]=-2;}
    prepare(48000.0);
}

bool RenderCore::prepare(double sampleRate) noexcept {
    if (!std::isfinite(sampleRate) || sampleRate < 8000.0 || sampleRate > 384000.0) return false;
    for(auto& voice:voices_)if(!voice.synth.prepare(sampleRate))return false;
    for (auto& track : tracks_) if (!track.effects.prepare(sampleRate)) return false;
    if (!limiter_.prepare(sampleRate)) return false;
    clickDelay_.reset();
    if(!sequence_.prepare(sampleRate,&sampleKernel_))return false;
    if(!returnA_.prepare(static_cast<float>(sampleRate))||!returnB_.prepare(static_cast<float>(sampleRate)))return false;
    returnConfig_[fx::ReverbMix]=1;returnConfig_[fx::DelayMix]=1;
    returnA_.set(returnConfig_.values,static_cast<float>(sampleRate));returnA_.reset();
    returnB_.set(returnConfig_.values,static_cast<float>(sampleRate),bpm_);returnB_.reset();
    sampleRate_ = sampleRate;
    updateLfoSteps();
    smoothing_ = static_cast<float>(1.0 - std::exp(-1.0 / (0.005 * sampleRate_)));
    return true;
}

bool RenderCore::valid(const Command& c) noexcept {
    if (!std::isfinite(c.value) || c.track < 0 || c.track >= kTrackCount) return false;
    switch (c.type) {
        case CommandType::NoteOn:
            return c.note >= 0 && c.note <= 127 && c.noteId != 0 && c.value >= 0.0f && c.value <= 1.0f;
        case CommandType::NoteOff: return c.noteId != 0;
        case CommandType::MasterGain: return c.value >= 0.0f && c.value <= 1.0f;
        case CommandType::TrackGain: return c.value >= 0.0f && c.value <= 2.0f;
        case CommandType::TrackPan: return c.value >= -1.0f && c.value <= 1.0f;
        case CommandType::TrackMute:
        case CommandType::TrackSolo:
        case CommandType::Transport:
        case CommandType::Metronome: return c.value == 0.0f || c.value == 1.0f;
        case CommandType::Tempo: return c.value >= 30.0f && c.value <= 300.0f;
        case CommandType::PatchSnapshot: return c.note >= 0 && c.note < kPatchSlots && c.frame == kImmediate;
        case CommandType::FxSnapshot: return c.note >= 0 && c.note < kFxSlots && c.frame == kImmediate;
        case CommandType::LimiterDrive: return c.value >= 0 && c.value <= 24;
        case CommandType::LimiterRelease: return c.value >= 20 && c.value <= 500;
        case CommandType::MeterTrack: return c.value >= 0 && c.value <= kTrackCount && c.value == std::floor(c.value);
        case CommandType::ProjectSnapshot: return c.note>=0&&c.note<3&&c.frame==kImmediate;
        case CommandType::LaunchClip: case CommandType::LaunchScene: return c.note>=-1&&c.note<8&&c.value>=0&&c.value<=24;
        case CommandType::Seek: return c.value>=0&&c.value<=65536;
        case CommandType::Preview:return c.note>=0&&c.note<project::kAssets&&c.value>=0&&c.value<=1;
        case CommandType::StopPreview:return true;
        case CommandType::SendA: case CommandType::SendB: return c.value>=0&&c.value<=1;
    }
    return false;
}

bool RenderCore::setPatch(int track, const synth::Patch& patch) noexcept {
    if (track < 0 || track >= kTrackCount || !patch.valid()) return false;
    for (std::size_t i = 0; i < patchSlots_.size(); ++i) {
        bool expected = false;
        if (!patchSlots_[i].busy.compare_exchange_strong(expected, true, std::memory_order_acquire)) continue;
        patchSlots_[i].patch = patch;
        // The command queue release/acquire publishes the complete immutable slot.
        if (enqueue({CommandType::PatchSnapshot, track, static_cast<int32_t>(i), 0, 0})) return true;
        patchSlots_[i].busy.store(false, std::memory_order_release);
        return false;
    }
    // Backpressure: the producer keeps its old patch and may try again.
    return false;
}
bool RenderCore::setEffects(int track, const fx::Config& config) noexcept {
    if(track<0||track>=kTrackCount||!config.valid())return false;
    for(std::size_t i=0;i<fxSlots_.size();++i){bool expected=false;
        if(!fxSlots_[i].busy.compare_exchange_strong(expected,true,std::memory_order_acquire))continue;
        fxSlots_[i].config=config;
        if(enqueue({CommandType::FxSnapshot,track,static_cast<int32_t>(i),0,0}))return true;
        fxSlots_[i].busy.store(false,std::memory_order_release);return false;}
    return false;
}
void RenderCore::reclaimRetiredPlans()noexcept{
    for(auto& slot:planSlots_)if(!slot.busy.load(std::memory_order_acquire))slot.plan.reset();
}
bool RenderCore::setProject(std::span<const double> data,const project::Samples& samples) noexcept {
    reclaimRetiredPlans();
    for(int i=0;i<3;++i){bool expected=false;if(!planSlots_[i].busy.compare_exchange_strong(expected,true,std::memory_order_acquire))continue;
        auto plan=project::decodePlan(data,samples);if(!plan){planSlots_[i].busy.store(false,std::memory_order_release);return false;}
        planSlots_[i].plan=std::move(plan);
        if(enqueue({CommandType::ProjectSnapshot,0,i,0,0}))return true;
        planSlots_[i].busy.store(false,std::memory_order_release);return false;
    }return false;
}
void RenderCore::sequenceNote(void* owner,int track,int key,float velocity,uint32_t id,bool on) noexcept {
    auto& core=*static_cast<RenderCore*>(owner);
    if(on)core.noteOn({CommandType::NoteOn,track,key,id,velocity});
    else for(auto& v:core.voices_)if(v.active&&v.track==track&&v.id==id)core.releaseVoice(v);
}
void RenderCore::sequenceAutomation(void* owner,int track,int lane,float value,bool enabled) noexcept {
    auto& core=*static_cast<RenderCore*>(owner);auto& t=core.tracks_[track];t.autoValues[lane]=value;t.automated[lane]=enabled;
    if(lane<2)core.updateTrackTargets();
    else if(lane==2){t.renderPatch=t.patch;if(enabled)t.renderPatch[synth::Filter1Cutoff]=value;}
    else core.refreshAutomation(track);
}
void RenderCore::refreshAutomation(int index) noexcept {
    auto& t=tracks_[index];t.renderPatch=t.patch;
    if(t.automated[2])t.renderPatch[synth::Filter1Cutoff]=t.autoValues[2];
    auto effect=t.fxConfig;if(t.automated[3])effect.macros[0].value=t.autoValues[3];t.effects.configure(effect);
}
void RenderCore::recyclePatch(const Command& c) noexcept {
    if (c.type == CommandType::PatchSnapshot) patchSlots_[c.note].busy.store(false, std::memory_order_release);
}
void RenderCore::updateLfoSteps() noexcept {
    for (auto& track : tracks_) for (int i = 0; i < 4; ++i)
        track.lfoSteps[i] = synth::lfoStep(track.patch, i, bpm_, sampleRate_);
}

bool RenderCore::enqueue(Command command) noexcept {
    if (!valid(command)) return false;
    command.order = nextOrder_++;
    if (commands_.push(command)) return true;
    dropped_.fetch_add(1, std::memory_order_relaxed);
    // Never lose a note-off silently and leave a sustained voice ringing.
    panic();
    return false;
}

bool RenderCore::schedule(const Command& c) noexcept {
    if (scheduledSize_ == scheduled_.size()) return false;
    scheduled_[scheduledSize_++] = c;
    std::push_heap(scheduled_.begin(), scheduled_.begin() + static_cast<std::ptrdiff_t>(scheduledSize_), Later{});
    return true;
}

Command RenderCore::popScheduled() noexcept {
    std::pop_heap(scheduled_.begin(), scheduled_.begin() + static_cast<std::ptrdiff_t>(scheduledSize_), Later{});
    return scheduled_[--scheduledSize_];
}

void RenderCore::resetSound() noexcept {
    sequence_.stop(true);preview_.silence();
    for (auto& voice : voices_) { voice.active = false; voice.synth.silence(); voice.sample.silence(); }
    // Pop in submission/frame order: several accepted edits may target one track.
    while (scheduledSize_ != 0) {
        const auto event = popScheduled();
        if (event.type != CommandType::NoteOn && event.type != CommandType::NoteOff && event.type != CommandType::Preview && event.type != CommandType::Transport) apply(event);
    }
    clickEnvelope_ = 0.0f;
    for (auto& track : tracks_) track.effects.reset();
    limiter_.reset();
    returnA_.reset();returnB_.reset();
    clickDelay_.reset();
}

void RenderCore::consumeCommands() noexcept {
    Command command;
    if (panic_.exchange(false, std::memory_order_acq_rel)) {
        resetSound();
        playing_ = false;
        // Bounded drain, even if the producer is continuously active.
        for (std::size_t i = 0; i < kQueueCapacity && commands_.pop(command); ++i) {
            // Panic stops notes, but an accepted patch must still match the UI.
            if (command.type != CommandType::NoteOn && command.type != CommandType::NoteOff && command.type != CommandType::Preview && command.type != CommandType::Transport) apply(command);
        }
        return;
    }
    for (int i = 0; i < kCommandsPerCallback && commands_.pop(command); ++i) {
        if ((command.type == CommandType::NoteOn || command.type == CommandType::NoteOff || command.type == CommandType::Preview) && command.order < minNoteOrder_) continue;
        if (command.frame == kImmediate) command.frame = frame_;
        if (!schedule(command)) {
            dropped_.fetch_add(1, std::memory_order_relaxed);
            resetSound();
            if (command.type != CommandType::NoteOn && command.type != CommandType::NoteOff && command.type != CommandType::Preview && command.type != CommandType::Transport) apply(command);
            playing_ = false;
            // Flush the pending queue next callback as a second safety boundary.
            panic();
            break;
        }
    }
}

void RenderCore::updateTrackTargets() noexcept {
    bool anySolo = false;
    for (const auto& track : tracks_) anySolo |= track.solo;
    for (auto& track : tracks_) {
        const float gain = track.mute || (anySolo && !track.solo) ? 0.0f : (track.automated[0]?track.autoValues[0]:track.gain);
        const float angle = ((track.automated[1]?track.autoValues[1]:track.pan) + 1.0f) * std::numbers::pi_v<float> * 0.25f;
        track.targetLeft = gain * std::cos(angle);
        track.targetRight = gain * std::sin(angle);
    }
}

void RenderCore::releaseVoice(Voice& voice,bool force) noexcept {
    if(voice.sampled)voice.sample.release(force);else voice.synth.release();
}

void RenderCore::noteOn(const Command& c) noexcept {
    const project::Plan* plan=activePlan_>=0?planSlots_[activePlan_].plan.get():nullptr;
    if(plan&&plan->mode==1&&plan->frozen[c.track].asset>=0)return;
    const sampler::Instrument* instrument=plan?&plan->instruments[c.track]:nullptr;
    const sampler::Zone* zone=nullptr;const std::array<sampler::Zone,3>* extra=nullptr;
    if(instrument&&instrument->mode){
        if(instrument->mode==2){if(c.note<36||c.note>=52)return;zone=&instrument->pads[c.note-36];}else zone=&instrument->sample;
        extra=&instrument->layers[instrument->mode==2?c.note-35:0];
        bool hasSource=false;unsigned chokeMask=0;
        const auto inspect=[&](const sampler::Zone& z){if(z.asset>=0&&plan->samples[z.asset]){hasSource=true;if(z.choke>0)chokeMask|=1u<<z.choke;}};
        inspect(*zone);for(const auto& z:*extra)inspect(z);if(!hasSource)return;
        if(instrument->mode==2&&chokeMask)for(auto& v:voices_)if(v.active&&v.sampled&&v.track==c.track)v.sample.choke(chokeMask);
    }
    Voice* selected = nullptr;
    for (auto& voice : voices_) {
        if (voice.active && voice.id == c.noteId && voice.track == c.track) {
            selected = &voice;
            break;
        }
        if (!voice.active && selected == nullptr) selected = &voice;
    }
    if (selected == nullptr) {
        // Prefer a releasing/quiet voice across both instrument families.
        const auto releasing=[](const Voice& v){return v.sampled?v.sample.releasing():v.synth.releasing();};
        const auto amplitude=[](const Voice& v){return v.sampled?v.sample.amplitude():v.synth.amplitude();};
        selected=&voices_[0];
        for(auto& voice:voices_)if((releasing(voice)&&!releasing(*selected))||
            (releasing(voice)==releasing(*selected)&&(amplitude(voice)<amplitude(*selected)||
            (amplitude(voice)==amplitude(*selected)&&voice.age<selected->age))))selected=&voice;
    }
    selected->id = c.noteId;
    selected->track = c.track;
    selected->note = c.note;
    const auto& track = tracks_[c.track];
    selected->sampled=zone!=nullptr;
    if(zone){selected->synth.silence();selected->sample.start(plan->samples,*zone,*extra,c.note,c.value,sampleRate_,instrument->mode==2);}
    else {selected->sample.silence();selected->synth.tables(plan?plan->table(c.track,0):nullptr,plan?plan->table(c.track,1):nullptr);selected->synth.start(c.note, c.value, c.noteId * 2654435761U, track.renderPatch, sampleRate_, track.freePhases, bpm_);}
    selected->active = true;
    selected->age = voiceAge_++;
}

void RenderCore::apply(const Command& c) noexcept {
    auto& track = tracks_[static_cast<std::size_t>(c.track)];
    switch (c.type) {
        case CommandType::NoteOn: noteOn(c); break;
        case CommandType::NoteOff:
            for (auto& voice : voices_) {
                if (voice.active && voice.id == c.noteId && voice.track == c.track) releaseVoice(voice);
            }
            break;
        case CommandType::MasterGain: targetMaster_ = c.value; break;
        case CommandType::TrackGain: track.gain = c.value; updateTrackTargets(); break;
        case CommandType::TrackPan: track.pan = c.value; updateTrackTargets(); break;
        case CommandType::TrackMute: track.mute = c.value != 0.0f; updateTrackTargets(); break;
        case CommandType::TrackSolo: track.solo = c.value != 0.0f; updateTrackTargets(); break;
        case CommandType::Tempo:
            baseBpm_=c.value;tempoClock_.reset();updateTempo(c.value);
            break;
        case CommandType::FxSnapshot:
            track.fxConfig=fxSlots_[c.note].config;
            refreshAutomation(c.track);
            fxSlots_[c.note].busy.store(false,std::memory_order_release);
            break;
        case CommandType::LimiterDrive: targetLimiterDrive_=fx::dbGain(c.value); break;
        case CommandType::LimiterRelease: limiter_.setRelease(c.value); break;
        case CommandType::MeterTrack: meterTrack_=static_cast<int>(c.value); break;
        case CommandType::ProjectSnapshot: {
            const auto* next=planSlots_[c.note].plan.get();
            if(preview_.active()&&next->samples[preview_.asset()].get()!=preview_.source())preview_.silence();
            // Retire every raw sample pointer before its owning old plan can be reclaimed.
            for(auto& v:voices_)if(v.active){const int mode=next->instruments[v.track].mode;
                const int oldMode=activePlan_>=0?planSlots_[activePlan_].plan->instruments[v.track].mode:0;
                if((next->mode==1&&next->frozen[v.track].asset>=0)||mode!=oldMode||(!v.sampled&&!v.synth.usesTables(next->table(v.track,0),next->table(v.track,1)))||(v.sampled&&!v.sample.references(next->samples))){v.active=false;v.sample.silence();v.synth.silence();}}
            sequence_.activate(next);tempoClock_.reset();const int old=activePlan_;activePlan_=c.note;
            if(old>=0)planSlots_[old].busy.store(false,std::memory_order_release);
            break;
        }
        case CommandType::LaunchClip: sequence_.launch(c.track,c.note,beat_,c.value);break;
        case CommandType::LaunchScene: sequence_.scene(c.note,beat_,c.value);break;
        case CommandType::Seek: beat_=c.value;nextClickBeat_=std::ceil(beat_/sequence_.beatUnit())*sequence_.beatUnit();sequence_.stop();sequence_.resetFrozen();break;
        case CommandType::SendA: track.targetSendA=c.value;break;
        case CommandType::SendB: track.targetSendB=c.value;break;
        case CommandType::PatchSnapshot:
            track.patch = patchSlots_[c.note].patch;
            refreshAutomation(c.track);
            recyclePatch(c);
            updateLfoSteps();
            break;
        case CommandType::Transport:
            playing_ = c.value != 0.0f;
            if (playing_) {
                beat_ = 0.0; nextClickBeat_ = 0.0;sequence_.rewind();
                for (auto& t : tracks_) for (int l = 0; l < 4; ++l)
                    if (t.patch[synth::Lfo1Sync + l * 4] != 0) t.freePhases[l] = 0;
            }
            else {
                // Cancel notes submitted BEFORE Stop, while preserving a new
                // pattern submitted after it in the same control batch.
                sequence_.stop();
                minNoteOrder_ = c.order + 1;
                auto end = std::remove_if(scheduled_.begin(), scheduled_.begin() + static_cast<std::ptrdiff_t>(scheduledSize_),
                    [this](const Command& event) {
                        return event.order < minNoteOrder_ &&
                            (event.type == CommandType::NoteOn || event.type == CommandType::NoteOff || event.type == CommandType::Preview);
                    });
                scheduledSize_ = static_cast<std::size_t>(end - scheduled_.begin());
                std::make_heap(scheduled_.begin(), end, Later{});
                clickEnvelope_ = 0.0f;
                for (auto& voice : voices_) if (voice.active) releaseVoice(voice,true);
            }
            break;
        case CommandType::Preview:{
            if(activePlan_>=0){const auto& sample=planSlots_[activePlan_].plan->samples[c.note];if(sample){sampler::Zone z;z.asset=c.note;z.last=std::min<int64_t>(sample->frames(),sample->rate*10LL);z.loopLast=z.last;z.attack=.002f;z.sustain=1;z.oneShot=true;preview_.start(sample.get(),z,60,c.value,sampleRate_,false);}}break;}
        case CommandType::StopPreview:preview_.choke();break;
        case CommandType::Metronome: metronome_ = c.value != 0.0f; break;
    }
}

float RenderCore::oscillator(double phase) const noexcept {
    const auto position = phase * 2048.0;
    const auto index = static_cast<std::size_t>(position);
    const auto fraction = static_cast<float>(position - static_cast<double>(index));
    return sine_[index] + fraction * (sine_[index + 1] - sine_[index]);
}

void RenderCore::updateTempo(float bpm)noexcept {
    if(bpm_==bpm)return;
    bpm_=bpm;updateLfoSteps();
    for(auto& t:tracks_)t.effects.tempo(bpm_);
    returnB_.set(returnConfig_.values,static_cast<float>(sampleRate_),bpm_);
}
void RenderCore::render(float* output, int32_t frames) noexcept {
    if (output == nullptr || frames <= 0) return;
    consumeCommands();
    float peakL = 0.0f;
    float peakR = 0.0f;
    int events = 0;
    for (int32_t i = 0; i < frames; ++i) {
        while (scheduledSize_ != 0 && scheduled_[0].frame <= frame_ && events < kEventsPerCallback) {
            const auto command = popScheduled();
            if (command.frame < frame_) ++late_;
            apply(command);
            ++events;
        }
        std::array<fx::Stereo,kTrackCount> clipAudio{};
        const auto* plan=activePlan_>=0?planSlots_[activePlan_].plan.get():nullptr;
        const auto* tempo=plan&&plan->mode==1?&plan->tempo:nullptr;
        if(playing_){if(sequence_.loop(beat_))nextClickBeat_=beat_;
            const float live=tempo?static_cast<float>(tempo->bpmAt(beat_,baseBpm_)):baseBpm_;
            if((frame_&31)==0||std::abs(live-bpm_)>.1f)updateTempo(live);
            sequence_.tick(beat_,baseBpm_,clipAudio);}
        else if(bpm_!=baseBpm_)updateTempo(baseBpm_);
        sequence_.renderFrozen(beat_,baseBpm_,playing_,clipAudio);
        std::array<float, kTrackCount> mono{};
        for (auto& voice : voices_) {
            if (!voice.active) continue;
            auto& t = tracks_[static_cast<std::size_t>(voice.track)];
            if(voice.sampled){clipAudio[voice.track]=clipAudio[voice.track]+voice.sample.tick(sampleKernel_);voice.active=voice.sample.active();}
            else{mono[static_cast<std::size_t>(voice.track)] += voice.synth.render(t.renderPatch, wavetables_, t.freePhases, bpm_);voice.active = voice.synth.active();}
        }
        std::array<fx::Stereo,kTrackCount> dry{};
        for(int t=0;t<kTrackCount;++t)dry[t]=clipAudio[t]+fx::Stereo{mono[t],mono[t]};
        if(sourceTrack_>=0&&sourceTrack_<kTrackCount){
            // Source bounces exclude inserts/sends/master; avoid doing their DSP only to discard it.
            for(auto& track:tracks_)for(int l=0;l<4;++l)track.freePhases[l]=synth::wrap(track.freePhases[l]+track.lfoSteps[l]);
            if(playing_)beat_=tempoClock_.tick(tempo,beat_,baseBpm_,sampleRate_);
            const auto value=dry[sourceTrack_];output[2*i]=value.l;output[2*i+1]=value.r;
            peakL=std::max(peakL,std::abs(value.l));peakR=std::max(peakR,std::abs(value.r));++frame_;continue;
        }
        fx::Stereo sendA{},sendB{};
        float left = 0.0f;
        float right = 0.0f;
        for (std::size_t t = 0; t < tracks_.size(); ++t) {
            auto& track = tracks_[t];
            for (int l = 0; l < 4; ++l) track.freePhases[l] = synth::wrap(track.freePhases[l] + track.lfoSteps[l]);
            track.left += smoothing_ * (track.targetLeft - track.left);
            track.right += smoothing_ * (track.targetRight - track.right);
            const int detectorTrack=track.effects.detectorTrack();
            const auto detector=detectorTrack>=0?dry[static_cast<std::size_t>(detectorTrack)]:dry[t];
            const auto effected=track.effects.tick(dry[t],detector);
            const fx::Stereo mixed{effected.l*track.left,effected.r*track.right};
            left += mixed.l; right += mixed.r;
            track.sendA+=smoothing_*(track.targetSendA-track.sendA);track.sendB+=smoothing_*(track.targetSendB-track.sendB);
            sendA=sendA+mixed*track.sendA;sendB=sendB+mixed*track.sendB;
            if(meterTrack_==static_cast<int>(t))analyzer_.push(mixed,meterTrack_);
        }
        const auto returns=returnA_.tick(sendA)+returnB_.tick(sendB);left+=returns.l;right+=returns.r;
        if (playing_) {
            if (beat_ >= nextClickBeat_) {
                if (metronome_) { clickEnvelope_ = 0.16f; clickPhase_ = 0.0; }
                nextClickBeat_ = (std::floor(beat_/sequence_.beatUnit())+1)*sequence_.beatUnit();
            }
            beat_=tempoClock_.tick(tempo,beat_,baseBpm_,sampleRate_);
        }
        fx::Stereo clickSample{};
        if (clickEnvelope_ > 0.00001f) {
            const float click = oscillator(clickPhase_) * clickEnvelope_;
            clickSample={click,click};
            clickPhase_ += 1500.0 / sampleRate_;
            if (clickPhase_ >= 1.0) clickPhase_ -= 1.0;
            clickEnvelope_ *= 0.994f;
        } else clickEnvelope_ = 0.0f;
        clickSample=clickDelay_.tick(clickSample);left+=clickSample.l;right+=clickSample.r;
        master_ += smoothing_ * (targetMaster_ - master_);
        limiterDrive_ += smoothing_ * (targetLimiterDrive_ - limiterDrive_);
        const auto preview=preview_.tick(sampleKernel_);left+=preview.l;right+=preview.r;
        left *= master_*limiterDrive_;
        right *= master_*limiterDrive_;
        if (std::abs(left) > kCeiling || std::abs(right) > kCeiling) ++clipped_;
        const auto limited=limiter_.tick({left,right});left=limited.l;right=limited.r;
        if(meterTrack_==kTrackCount)analyzer_.push(limited,kTrackCount);
        output[2 * i] = left;
        output[2 * i + 1] = right;
        peakL = std::max(peakL, std::abs(left));
        peakR = std::max(peakR, std::abs(right));
        ++frame_;
    }
    publish(peakL, peakR);
}

void RenderCore::publish(float left, float right) noexcept {
    // Prevent slowly decaying parameter smoothers entering the denormal range.
    for (auto& track : tracks_) {
        if (track.targetLeft == 0.0f && std::abs(track.left) < 1e-12f) track.left = 0.0f;
        if (track.targetRight == 0.0f && std::abs(track.right) < 1e-12f) track.right = 0.0f;
    }
    if (targetMaster_ == 0.0f && std::abs(master_) < 1e-12f) master_ = 0.0f;
    for(int t=0;t<kTrackCount;++t){publishedReduction_[t].store(tracks_[t].effects.reduction(),std::memory_order_relaxed);
        const auto bands=tracks_[t].effects.bandMeters();
        for(int b=0;b<3;++b)publishedBands_[t*3+b].store(bands[b],std::memory_order_relaxed);}
    publishedLimiter_.store(limiter_.reduction(),std::memory_order_relaxed);
    const auto seq=sequence_.snapshot();publishedMode_.store(seq.mode,std::memory_order_relaxed);
    for(int t=0;t<kTrackCount;++t){publishedClips_[t].store(seq.active[t],std::memory_order_relaxed);publishedQueued_[t].store(seq.queued[t],std::memory_order_relaxed);publishedPhase_[t].store(seq.phase[t],std::memory_order_relaxed);}
    uint32_t count = 0;
    for (const auto& voice : voices_) if (voice.active) ++count;
    publishedFrame_.store(frame_, std::memory_order_relaxed);
    publishedBeat_.store(static_cast<uint64_t>(beat_ * 1000000.0), std::memory_order_relaxed);
    publishedVoices_.store(count, std::memory_order_relaxed);
    publishedLate_.store(late_, std::memory_order_relaxed);
    publishedClipped_.store(clipped_, std::memory_order_relaxed);
    publishedLeft_.store(left, std::memory_order_relaxed);
    publishedRight_.store(right, std::memory_order_relaxed);
    publishedBpm_.store(bpm_, std::memory_order_relaxed);
    publishedPlaying_.store(playing_, std::memory_order_relaxed);
}

project::Snapshot RenderCore::sequenceSnapshot()const noexcept {
    project::Snapshot s;s.mode=publishedMode_.load(std::memory_order_relaxed);for(int t=0;t<kTrackCount;++t){s.active[t]=publishedClips_[t].load(std::memory_order_relaxed);s.queued[t]=publishedQueued_[t].load(std::memory_order_relaxed);s.phase[t]=publishedPhase_[t].load(std::memory_order_relaxed);}return s;
}
FxSnapshot RenderCore::effectsSnapshot() noexcept {
    FxSnapshot result;
    for(int t=0;t<kTrackCount;++t)result.sidechain[t]=publishedReduction_[t].load(std::memory_order_relaxed);
    for(int b=0;b<kTrackCount*3;++b)result.bands[b]=publishedBands_[b].load(std::memory_order_relaxed);
    result.limiterReduction=publishedLimiter_.load(std::memory_order_relaxed);
    result.latency=static_cast<float>(outputLatencyFrames());result.sampleRate=static_cast<float>(sampleRate_);
    result.spectrum=analyzer_.analyze(result.sampleRate);result.meterTrack=analyzer_.track();result.analyzerDrops=analyzer_.dropped();return result;
}

CoreSnapshot RenderCore::snapshot() const noexcept {
    return {publishedFrame_.load(std::memory_order_relaxed), publishedBeat_.load(std::memory_order_relaxed),
            publishedVoices_.load(std::memory_order_relaxed), dropped_.load(std::memory_order_relaxed),
            publishedLate_.load(std::memory_order_relaxed), publishedClipped_.load(std::memory_order_relaxed),
            publishedLeft_.load(std::memory_order_relaxed), publishedRight_.load(std::memory_order_relaxed),
            publishedBpm_.load(std::memory_order_relaxed), publishedPlaying_.load(std::memory_order_relaxed)};
}
} // namespace astra
