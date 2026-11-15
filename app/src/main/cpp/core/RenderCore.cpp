#include "RenderCore.h"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace astra {
namespace {
constexpr float kCeiling = 0.988553095f; // -0.1 dBFS sample clamp; NOT a mastering limiter.
constexpr int kCommandsPerCallback = 256;
constexpr int kEventsPerCallback = 512;
}

RenderCore::RenderCore() noexcept {
    for (std::size_t i = 0; i < sine_.size(); ++i) {
        sine_[i] = static_cast<float>(std::sin(2.0 * std::numbers::pi * static_cast<double>(i) / 2048.0));
    }
    prepare(48000.0);
}

bool RenderCore::prepare(double sampleRate) noexcept {
    if (!std::isfinite(sampleRate) || sampleRate < 8000.0 || sampleRate > 384000.0) return false;
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
        case CommandType::PatchSnapshot: return c.note >= 0 && c.note < 64 && c.frame == kImmediate;
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
    for (auto& voice : voices_) { voice.active = false; voice.synth.silence(); }
    // Pop in submission/frame order: several accepted edits may target one track.
    while (scheduledSize_ != 0) {
        const auto event = popScheduled();
        if (event.type == CommandType::PatchSnapshot) apply(event);
    }
    clickEnvelope_ = 0.0f;
}

void RenderCore::consumeCommands() noexcept {
    Command command;
    if (panic_.exchange(false, std::memory_order_acq_rel)) {
        resetSound();
        playing_ = false;
        // Bounded drain, even if the producer is continuously active.
        for (std::size_t i = 0; i < kQueueCapacity && commands_.pop(command); ++i) {
            // Panic stops notes, but an accepted patch must still match the UI.
            if (command.type == CommandType::PatchSnapshot) apply(command);
        }
        return;
    }
    for (int i = 0; i < kCommandsPerCallback && commands_.pop(command); ++i) {
        if ((command.type == CommandType::NoteOn || command.type == CommandType::NoteOff) && command.order < minNoteOrder_) continue;
        if (command.frame == kImmediate) command.frame = frame_;
        if (!schedule(command)) {
            dropped_.fetch_add(1, std::memory_order_relaxed);
            resetSound();
            if (command.type == CommandType::PatchSnapshot) apply(command);
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
        const float gain = track.mute || (anySolo && !track.solo) ? 0.0f : track.gain;
        const float angle = (track.pan + 1.0f) * std::numbers::pi_v<float> * 0.25f;
        track.targetLeft = gain * std::cos(angle);
        track.targetRight = gain * std::sin(angle);
    }
}

void RenderCore::releaseVoice(Voice& voice) noexcept {
    voice.synth.release();
}

void RenderCore::noteOn(const Command& c) noexcept {
    Voice* selected = nullptr;
    for (auto& voice : voices_) {
        if (voice.active && voice.id == c.noteId && voice.track == c.track) {
            selected = &voice;
            break;
        }
        if (!voice.active && selected == nullptr) selected = &voice;
    }
    if (selected == nullptr) {
        // Prefer a releasing/quiet voice. Stable oldest-voice tie breaking.
        selected = &voices_[0];
        for (auto& voice : voices_) {
            if ((voice.synth.releasing() && !selected->synth.releasing()) ||
                (voice.synth.releasing() == selected->synth.releasing() &&
                 (voice.synth.amplitude() < selected->synth.amplitude() ||
                  (voice.synth.amplitude() == selected->synth.amplitude() && voice.age < selected->age)))) selected = &voice;
        }
    }
    selected->id = c.noteId;
    selected->track = c.track;
    selected->note = c.note;
    const auto& track = tracks_[c.track];
    selected->synth.start(c.note, c.value, c.noteId * 2654435761U, track.patch, sampleRate_, track.freePhases, bpm_);
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
        case CommandType::Tempo: bpm_ = c.value; updateLfoSteps(); break;
        case CommandType::PatchSnapshot:
            track.patch = patchSlots_[c.note].patch;
            recyclePatch(c);
            updateLfoSteps();
            break;
        case CommandType::Transport:
            playing_ = c.value != 0.0f;
            if (playing_) {
                beat_ = 0.0; nextClickBeat_ = 0.0;
                for (auto& t : tracks_) for (int l = 0; l < 4; ++l)
                    if (t.patch[synth::Lfo1Sync + l * 4] != 0) t.freePhases[l] = 0;
            }
            else {
                // Cancel notes submitted BEFORE Stop, while preserving a new
                // pattern submitted after it in the same control batch.
                minNoteOrder_ = c.order + 1;
                auto end = std::remove_if(scheduled_.begin(), scheduled_.begin() + static_cast<std::ptrdiff_t>(scheduledSize_),
                    [this](const Command& event) {
                        return event.order < minNoteOrder_ &&
                            (event.type == CommandType::NoteOn || event.type == CommandType::NoteOff);
                    });
                scheduledSize_ = static_cast<std::size_t>(end - scheduled_.begin());
                std::make_heap(scheduled_.begin(), end, Later{});
                clickEnvelope_ = 0.0f;
                for (auto& voice : voices_) if (voice.active) releaseVoice(voice);
            }
            break;
        case CommandType::Metronome: metronome_ = c.value != 0.0f; break;
    }
}

float RenderCore::oscillator(double phase) const noexcept {
    const auto position = phase * 2048.0;
    const auto index = static_cast<std::size_t>(position);
    const auto fraction = static_cast<float>(position - static_cast<double>(index));
    return sine_[index] + fraction * (sine_[index + 1] - sine_[index]);
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
        std::array<float, kTrackCount> mono{};
        for (auto& voice : voices_) {
            if (!voice.active) continue;
            auto& t = tracks_[static_cast<std::size_t>(voice.track)];
            mono[static_cast<std::size_t>(voice.track)] += voice.synth.render(t.patch, wavetables_, t.freePhases, bpm_);
            voice.active = voice.synth.active();
        }
        float left = 0.0f;
        float right = 0.0f;
        for (std::size_t t = 0; t < tracks_.size(); ++t) {
            auto& track = tracks_[t];
            for (int l = 0; l < 4; ++l) track.freePhases[l] = synth::wrap(track.freePhases[l] + track.lfoSteps[l]);
            track.left += smoothing_ * (track.targetLeft - track.left);
            track.right += smoothing_ * (track.targetRight - track.right);
            left += mono[t] * track.left;
            right += mono[t] * track.right;
        }
        if (playing_) {
            if (beat_ >= nextClickBeat_) {
                if (metronome_) { clickEnvelope_ = 0.16f; clickPhase_ = 0.0; }
                nextClickBeat_ = std::floor(beat_) + 1.0;
            }
            beat_ += static_cast<double>(bpm_) / (60.0 * sampleRate_);
        }
        if (clickEnvelope_ > 0.00001f) {
            const float click = oscillator(clickPhase_) * clickEnvelope_;
            left += click;
            right += click;
            clickPhase_ += 1500.0 / sampleRate_;
            if (clickPhase_ >= 1.0) clickPhase_ -= 1.0;
            clickEnvelope_ *= 0.994f;
        } else clickEnvelope_ = 0.0f;
        master_ += smoothing_ * (targetMaster_ - master_);
        left *= master_;
        right *= master_;
        if (std::abs(left) > kCeiling || std::abs(right) > kCeiling) ++clipped_;
        left = std::clamp(left, -kCeiling, kCeiling);
        right = std::clamp(right, -kCeiling, kCeiling);
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

CoreSnapshot RenderCore::snapshot() const noexcept {
    return {publishedFrame_.load(std::memory_order_relaxed), publishedBeat_.load(std::memory_order_relaxed),
            publishedVoices_.load(std::memory_order_relaxed), dropped_.load(std::memory_order_relaxed),
            publishedLate_.load(std::memory_order_relaxed), publishedClipped_.load(std::memory_order_relaxed),
            publishedLeft_.load(std::memory_order_relaxed), publishedRight_.load(std::memory_order_relaxed),
            publishedBpm_.load(std::memory_order_relaxed), publishedPlaying_.load(std::memory_order_relaxed)};
}
} // namespace astra
