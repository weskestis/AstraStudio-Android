#pragma once
#include "SynthPatch.h"
#include "DspMath.h"
#include <algorithm>
#include <cmath>

namespace astra::synth {
class Envelope {
public:
    void configure(float attack, float decay, float sustain, float release, float sampleRate) noexcept {
        attackStep_ = 1.0f / std::max(1.0f, attack * sampleRate);
        decayStep_ = (1.0f - sustain) / std::max(1.0f, decay * sampleRate);
        sustain_ = sustain;
        const float newRelease = std::max(1.0f, release * sampleRate);
        // Only a changed release parameter may retime an existing release.
        if (stage_ == Stage::Release && newRelease != releaseSamples_) releaseStep_ = value_ / newRelease;
        releaseSamples_ = newRelease;
    }
    void trigger() noexcept { value_ = 0; stage_ = Stage::Attack; }
    void release() noexcept {
        if (stage_ != Stage::Idle && stage_ != Stage::Release) {
            releaseStep_ = value_ / releaseSamples_; stage_ = Stage::Release;
        }
    }
    float tick() noexcept {
        switch (stage_) {
            case Stage::Attack: value_ += attackStep_; if (value_ >= 1) { value_ = 1; stage_ = Stage::Decay; } break;
            case Stage::Decay:
                if (value_ <= sustain_) stage_ = Stage::Sustain;
                else { value_ = std::max(sustain_, value_ - decayStep_); if (value_ == sustain_) stage_ = Stage::Sustain; }
                break;
            case Stage::Sustain:
                value_ += .002f * (sustain_ - value_);
                if (value_ <= 1e-7f && sustain_ <= 1e-7f) { value_ = 0; stage_ = Stage::Idle; }
                break;
            case Stage::Release: value_ -= releaseStep_; if (value_ <= 1e-7f) { value_ = 0; stage_ = Stage::Idle; } break;
            case Stage::Idle: break;
        }
        return value_;
    }
    bool active() const noexcept { return stage_ != Stage::Idle; }
    bool releasing() const noexcept { return stage_ == Stage::Release; }
    float value() const noexcept { return value_; }
private:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };
    Stage stage_{Stage::Idle};
    float value_{}, attackStep_{}, decayStep_{}, sustain_{1}, releaseSamples_{1440}, releaseStep_{};
};
inline double lfoStep(const Patch& patch, int index, float bpm, double sampleRate) noexcept {
    const int base = Lfo1Rate + index * 4;
    return patch[base + 2] != 0 ? static_cast<double>(bpm) / (60.0 * sampleRate * patch[base+1]) : patch[base] / sampleRate;
}
inline float shapeValue(const std::array<float, kShapePoints>& shape, double phase) noexcept {
    const double pos = wrap(phase) * kShapePoints;
    const auto i = static_cast<int>(pos);
    return lerp(shape[i], shape[(i + 1) % kShapePoints], static_cast<float>(pos - i));
}
} // namespace astra::synth
