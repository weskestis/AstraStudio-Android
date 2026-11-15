#include "Filter.h"
#include "DspMath.h"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace astra::synth {
void StateVariable::set(float frequency, float resonance, float sampleRate, int rampSamples) noexcept {
    const float g = std::tan(std::numbers::pi_v<float> * std::clamp(frequency, 10.0f, .4f * sampleRate) / sampleRate);
    const float q = .5f + 9.0f * resonance * resonance;
    const float k = 1.0f / q;
    ramp_ = std::max(1, rampSamples);
    dg_ = (g - g_) / ramp_; dk_ = (k - k_) / ramp_;
}
float StateVariable::process(float x, int mode) noexcept {
    if (ramp_ > 0) { g_ += dg_; k_ += dk_; --ramp_; }
    // Trapezoidal state-variable recurrence; see docs/SYNTHESIS.md references.
    const float a = 1.0f / (1.0f + g_ * (g_ + k_));
    const float v3 = x - s2_;
    const float band = a * s1_ + g_ * a * v3;
    const float low = s2_ + g_ * s1_ * a + g_ * g_ * a * v3;
    s1_ = flush(2.0f * band - s1_); s2_ = flush(2.0f * low - s2_);
    if (mode == 2) return x - k_ * band - low;
    if (mode == 3) return k_ * band;
    return low;
}
void Filter::reset() noexcept {
    main_.reset(); for (auto& f : formants_) f.reset();
    delay_.fill(0); write_ = 0; damping_ = 0;
}
void Filter::configure(int mode, float cutoff, float resonance, float drive, float vowel, float sampleRate) noexcept {
    if (mode != mode_) { reset(); mode_ = mode; }
    ramp_ = 32;
    driveTarget_ = drive; driveStep_ = (driveTarget_ - drive_) / ramp_;
    if (mode >= 1 && mode <= 3) main_.set(cutoff, resonance, sampleRate, ramp_);
    if (mode == 4) {
        delayTarget_ = std::clamp(sampleRate / std::max(20.0f, cutoff), 2.0f, static_cast<float>(kDelay - 2));
        delayStep_ = (delayTarget_ - delaySamples_) / ramp_;
        feedbackTarget_ = resonance * .94f;
        feedbackStep_ = (feedbackTarget_ - feedback_) / ramp_;
    }
    if (mode == 5) {
        constexpr float vowels[5][3] = {{800,1150,2900},{400,1700,2600},{300,2200,3000},{500,800,2600},{350,650,2400}};
        const float position = unit(vowel) * 4;
        const auto a = static_cast<int>(position), b = std::min(4, a + 1);
        for (int i = 0; i < 3; ++i) formants_[i].set(lerp(vowels[a][i], vowels[b][i], position - a) * cutoff / 900.0f,
            .6f + resonance * .3f, sampleRate, ramp_);
    }
}
float Filter::process(float x) noexcept {
    if (ramp_ > 0) {
        drive_ += driveStep_; delaySamples_ += delayStep_; feedback_ += feedbackStep_;
        if (--ramp_ == 0) { drive_ = driveTarget_; delaySamples_ = delayTarget_; feedback_ = feedbackTarget_; }
    }
    if (mode_ == 0) return x;
    if (drive_ > .0001f) x = lerp(x, softClip(x * (1.0f + 12.0f * drive_)), unit(drive_));
    if (mode_ <= 3) return main_.process(x, mode_);
    if (mode_ == 4) {
        float read = static_cast<float>(write_) - delaySamples_;
        if (read < 0) read += kDelay;
        const auto a = static_cast<int>(read), b = (a + 1) & (kDelay - 1);
        const float delayed = lerp(delay_[a], delay_[b], read - a);
        damping_ = flush(damping_ + .35f * (delayed - damping_));
        delay_[write_] = softClip(x + feedback_ * damping_);
        write_ = (write_ + 1) & (kDelay - 1);
        return .6f * (x + delayed);
    }
    return .65f * formants_[0].process(x, 3) + .45f * formants_[1].process(x, 3) + .3f * formants_[2].process(x, 3);
}
} // namespace astra::synth
