#pragma once
#include "WavetableBank.h"
#include "DspMath.h"
#include <array>
#include <numbers>

namespace astra::synth {
// Four sine operators using phase modulation (the conventional digital FM
// implementation). Modulation index is in radians. Operator 4 has feedback.
class FMOperatorSynth {
public:
    void reset() noexcept { phase_.fill(0); feedback_ = 0; }
    float render(const WavetableBank& bank, const std::array<float, 4>& increments,
                 const std::array<float, 4>& levels, float index, float feedback, int algorithm) noexcept {
        const float scale = index / (2.0f * std::numbers::pi_v<float>);
        auto op = [&](int i, float mod) {
            return levels[i] * bank.sine(phase_[i] + mod * scale);
        };
        const float d = op(3, feedback_ * feedback);
        float out = 0;
        switch (algorithm) {
            case 0: { const float c = op(2, d), b = op(1, c); out = op(0, b); break; }
            case 1: out = .5f * (op(0, op(1, 0)) + op(2, d)); break;
            case 2: out = op(0, (op(1, 0) + op(2, 0) + d) / 3.0f); break;
            default: out = .25f * (op(0, 0) + op(1, 0) + op(2, 0) + d); break;
        }
        feedback_ = d;
        for (int i = 0; i < 4; ++i) phase_[i] = wrap(phase_[i] + increments[i]);
        return out;
    }
private:
    std::array<double, 4> phase_{};
    float feedback_{};
};
} // namespace astra::synth
