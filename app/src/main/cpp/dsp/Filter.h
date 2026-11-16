#pragma once
#include <array>
#include <memory>

namespace astra::synth {
class StateVariable {
public:
    void set(float frequency, float resonance, float sampleRate, int rampSamples) noexcept;
    float process(float x, int mode) noexcept;
    void reset() noexcept { s1_ = s2_ = 0; }
private:
    float g_{.1f}, k_{1.4f}, dg_{}, dk_{}, s1_{}, s2_{};
    int ramp_{};
};
class Filter {
public:
    // Off-render preparation; sampleRate is the synth's oversampled rate.
    bool prepare(float sampleRate) noexcept;
    void reset() noexcept;
    void configure(int mode, float cutoff, float resonance, float drive, float vowel, float sampleRate) noexcept;
    float process(float x) noexcept;
private:
    static constexpr int kDelay = 8192;
    StateVariable main_;
    std::array<StateVariable, 3> formants_{};
    std::array<float, kDelay> delay_{};
    std::unique_ptr<float[]> extendedDelay_;
    int delaySize_{kDelay};
    int mode_{}, write_{}, ramp_{};
    float delaySamples_{100}, delayTarget_{100}, delayStep_{};
    float feedback_{}, feedbackTarget_{}, feedbackStep_{}, damping_{};
    float dampingCoefficient_{.35f}, dampingRate_{96000};
    float drive_{}, driveTarget_{}, driveStep_{};
};
} // namespace astra::synth
