#include "SynthVoice.h"
#include "HalfBand.h"
#include "DspMath.h"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace astra::synth {
bool SynthVoice::prepare(double sampleRate) noexcept {
    for(auto& filter:filters_)if(!filter.prepare(static_cast<float>(sampleRate*2)))return false;
    return true;
}
void SynthVoice::start(int note, float velocity, uint32_t seed, const Patch& patch, double sampleRate,
                      const std::array<double, 4>& freePhases, float bpm) noexcept {
    tail_ = lastSample_; fade_ = std::abs(tail_) > 1e-6f ? 64 : 0;
    sampleRate_ = sampleRate;
    baseFrequency_ = static_cast<float>(440.0 * std::exp2((note - 69.0) / 12.0));
    velocity_ = velocity; key_ = (note - 60.0f) / 60.0f;
    smoothCoefficient_ = static_cast<float>(1.0 - std::exp(-16.0 / (.005 * sampleRate)));
    dcPole_ = static_cast<float>(std::exp(-2.0 * std::numbers::pi * 8.0 / sampleRate));
    random_ = seed ? seed : 1; phaseA_ = phaseB_ = phaseSub_ = 0;
    lfoPhase_.fill(0); smoothed_ = patch.values;
    for (auto& filter : filters_) filter.reset();
    fm_.reset(); fir_.fill(0); firWrite_ = 0; dcInput_ = dcOutput_ = 0;
    for (auto& e : envelopes_) e = Envelope{};
    update(patch, freePhases, bpm, true);
    for (auto& e : envelopes_) e.trigger();
    controlCountdown_ = 0;
}
void SynthVoice::release() noexcept { for (auto& env : envelopes_) env.release(); }
double SynthVoice::warped(double phase, int mode, float amount) noexcept {
    if (mode == 1) return wrap(phase * (1.0 + 7.0 * amount));
    if (mode == 2) return phase + .95 * amount * phase * (1.0 - phase);
    if (mode == 3) return phase - .95 * amount * phase * (1.0 - phase);
    return phase;
}
void SynthVoice::update(const Patch& p, const std::array<double, 4>& freePhases, float bpm, bool initial) noexcept {
    for (int i = 0; i < ParameterCount; ++i)
        smoothed_[i] = specs[i].integer ? p[i] : flush(smoothed_[i] + smoothCoefficient_ * (p[i] - smoothed_[i]));
    auto v = [&](int id) { return smoothed_[id]; };
    const std::array<int, 8> newModes{static_cast<int>(p[Instrument]), static_cast<int>(p[OscABank]), static_cast<int>(p[OscBBank]),
        static_cast<int>(p[OscAWarp]), static_cast<int>(p[OscBWarp]), static_cast<int>(p[Algorithm]),
        static_cast<int>(p[Filter1Type]), static_cast<int>(p[Filter2Type])};
    if (!initial && (newModes != modes_ || parallel_ != (p[FilterRouting] != 0))) { tail_ = lastSample_; fade_ = 64; }
    modes_ = newModes; parallel_ = p[FilterRouting] != 0;
    for (int i = 0; i < 3; ++i) {
        const int base = Env1Attack + i * 4;
        envelopes_[i].configure(p[base], p[base+1], p[base+2], p[base+3], static_cast<float>(sampleRate_));
    }
    for (int i = 0; i < 4; ++i) {
        const int base = Op1Attack + i * 6;
        envelopes_[3+i].configure(p[base], p[base+1], p[base+2], p[base+3], static_cast<float>(sampleRate_));
    }
    std::array<float, 10> sources{};
    for (int i = 0; i < 4; ++i) {
        lfoIncrement_[i] = lfoStep(p, i, bpm, sampleRate_);
        const double phase = p[Lfo1Retrigger + i*4] != 0 ? lfoPhase_[i] : freePhases[i];
        sources[i+1] = shapeValue(p.shapes[i], phase);
    }
    for (int i = 0; i < 3; ++i) sources[i+5] = envelopes_[i].value();
    sources[8] = velocity_; sources[9] = key_;
    std::array<float, static_cast<int>(Target::Count)> mod{};
    for (const auto& route : p.routes) mod[route.target] += sources[route.source] * route.amount;
    auto m = [&](Target target) { return std::clamp(mod[static_cast<int>(target)], -2.0f, 2.0f); };
    const float frequency = baseFrequency_ * std::exp2(2.0f * m(Target::Pitch));
    const float sr = static_cast<float>(sampleRate_ * 2);
    std::array<float, Count> target{};
    auto inc = [&](float hz) { return std::clamp(hz / sr, 0.000001f, .45f); };
    target[IncA] = inc(frequency * std::exp2((v(OscATune) + v(OscADetune) / 100.0f) / 12.0f));
    target[IncB] = inc(frequency * std::exp2((v(OscBTune) + v(OscBDetune) / 100.0f) / 12.0f));
    target[IncSub] = inc(frequency * .5f);
    target[PositionA] = unit(v(OscAPosition) + m(Target::PositionA));
    target[PositionB] = unit(v(OscBPosition) + m(Target::PositionB));
    target[Amount] = std::clamp(v(FmAmount) + 4.0f * m(Target::FmAmount), 0.0f, 4.0f);
    target[Index] = std::clamp(v(FmIndex) + 8.0f * m(Target::FmIndex), 0.0f, 8.0f);
    target[LevelA] = unit(v(OscALevel) + m(Target::LevelA));
    target[LevelB] = unit(v(OscBLevel) + m(Target::LevelB));
    target[Sub] = v(SubLevel); target[Noise] = v(NoiseLevel); target[Gain] = v(Output);
    target[WarpA] = unit(v(OscAWarpAmount) + m(Target::WarpA));
    target[WarpB] = unit(v(OscBWarpAmount) + m(Target::WarpB));
    const float stressA = 1.0f + (modes_[3] == 1 ? 7.0f : 2.0f) * target[WarpA];
    const float stressB = 1.0f + (modes_[4] == 1 ? 7.0f : 2.0f) * target[WarpB] + target[Amount] * 4.0f;
    target[MipA] = WavetableBank::mipFor(target[IncA] * sr * stressA, sr);
    target[MipB] = WavetableBank::mipFor(target[IncB] * sr * stressB, sr);
    target[Amplitude] = std::clamp(1.0f + m(Target::Amplitude), 0.0f, 2.0f);
    target[Feedback] = v(Feedback);
    for (int i = 0; i < 4; ++i) {
        target[OpInc1+i] = inc(frequency * v(Op1Ratio+i*6));
        target[OpLevel1+i] = v(Op1Level+i*6);
    }
    for (int i = 0; i < Count; ++i) {
        if (initial) controls_[i] = target[i];
        steps_[i] = (target[i] - controls_[i]) / 16.0f;
    }
    for (int i = 0; i < 2; ++i) {
        const int base = Filter1Type + i*5;
        const float cutoff = v(base+1) * std::exp2(6.0f * m(i == 0 ? Target::Cutoff1 : Target::Cutoff2));
        const float resonance = std::clamp(v(base+2) + m(i == 0 ? Target::Resonance1 : Target::Resonance2), 0.0f, .95f);
        const float drive = unit(v(base+3) + m(i == 0 ? Target::Drive1 : Target::Drive2));
        filters_[i].configure(modes_[6+i], cutoff, resonance, drive, v(base+4), sr);
    }
}
float SynthVoice::render(const Patch& p, const WavetableBank& bank,
                         const std::array<double, 4>& freePhases, float bpm) noexcept {
    if (controlCountdown_-- <= 0) { update(p, freePhases, bpm, false); controlCountdown_ = 15; }
    for (int i = 0; i < Count; ++i) controls_[i] += steps_[i];
    for (int i = 0; i < 4; ++i) lfoPhase_[i] = wrap(lfoPhase_[i] + lfoIncrement_[i]);
    const float envelope = envelopes_[0].tick();
    envelopes_[1].tick(); envelopes_[2].tick();
    std::array<float, 4> opLevels{}, increments{};
    for (int i = 0; i < 4; ++i) {
        opLevels[i] = controls_[OpLevel1+i] * envelopes_[3+i].tick();
        increments[i] = controls_[OpInc1+i];
    }
    for (int oversample = 0; oversample < 2; ++oversample) {
        float raw;
        if (modes_[0] == 0) {
            const float a = bank.read(modes_[1], controls_[PositionA], warped(phaseA_, modes_[3], controls_[WarpA]), controls_[MipA]);
            const float b = controls_[LevelB] > 1e-7f ? bank.read(modes_[2], controls_[PositionB],
                warped(wrap(phaseB_ + controls_[Amount] * a), modes_[4], controls_[WarpB]), controls_[MipB]) : 0;
            raw = a * controls_[LevelA] + b * controls_[LevelB];
        } else raw = fm_.render(bank, increments, opLevels, controls_[Index], controls_[Feedback], modes_[5]);
        if (controls_[Noise] > 1e-7f) {
            random_ ^= random_ << 13; random_ ^= random_ >> 17; random_ ^= random_ << 5;
            raw += (static_cast<float>(random_ >> 8) / 8388608.0f - 1.0f) * controls_[Noise];
        }
        const float filtered = parallel_ ? .5f * (filters_[0].process(raw) + filters_[1].process(raw)) : filters_[1].process(filters_[0].process(raw));
        // Sub bypasses the driven filters; it is still governed by the amp envelope.
        fir_[firWrite_] = filtered + bank.sine(phaseSub_) * controls_[Sub];
        firWrite_ = (firWrite_ + 1) & 31;
        phaseA_ = wrap(phaseA_ + controls_[IncA]); phaseB_ = wrap(phaseB_ + controls_[IncB]);
        phaseSub_ = wrap(phaseSub_ + controls_[IncSub]);
    }
    float sample = 0;
    for (int i = 0; i < 31; i += 2) sample += halfBand[i] * fir_[(firWrite_ - 1 - i) & 31];
    sample += halfBand[15] * fir_[(firWrite_ - 16) & 31];
    const float dc = sample - dcInput_ + dcPole_ * dcOutput_;
    dcInput_ = sample; dcOutput_ = flush(dc);
    sample = dc * envelope * velocity_ * controls_[Gain] * controls_[Amplitude] * .12f;
    if (fade_ > 0) { const float blend = static_cast<float>(fade_--) / 64.0f; sample = lerp(sample, tail_, blend); }
    if (!std::isfinite(sample)) sample = 0;
    lastSample_ = sample;
    return sample;
}
} // namespace astra::synth
