#include "WavetableBank.h"
#include "DspMath.h"
#include <algorithm>
#include <cmath>
#include <numbers>

namespace astra::synth {
WavetableBank::WavetableBank() noexcept {
    for (int i = 0; i <= kSize; ++i) sine_[i] = static_cast<float>(std::sin(2.0 * std::numbers::pi * i / kSize));
    for (int bank = 0; bank < kBanks; ++bank) for (int frame = 0; frame < kFrames; ++frame) {
        std::array<float, 129> amplitudes{};
        for (int h = 1; h <= 128; ++h) {
            float a = 0;
            if (bank == 0) {
                if (frame == 0) a = h == 1 ? 1.0f : 0.0f;
                if (frame == 1 && h % 2) a = (h % 4 == 1 ? 1.0f : -1.0f) / (h * h);
                if (frame == 2) a = 1.0f / h;
                if (frame == 3 && h % 2) a = 1.0f / h;
            } else if (bank == 1) {
                a = (h == 1 ? .6f : 0.0f) + ((h + frame) % (frame + 2) == 0 ? .9f : .08f) / std::sqrt(static_cast<float>(h));
                a *= std::exp(-h / 40.0f);
                if (h % 3 == 0) a = -a;
            } else {
                const float center1 = bank == 2 ? 3.0f + frame * 3 : 7.0f + frame * 5;
                const float center2 = bank == 2 ? 12.0f + frame * 7 : 23.0f + frame * 11;
                const float width = bank == 2 ? 2.5f : 1.2f;
                const float d1 = (h - center1) / width, d2 = (h - center2) / (width * 1.5f);
                a = (h == 1 ? .45f : .0f) + .5f * std::exp(-.5f * d1 * d1) + .3f * std::exp(-.5f * d2 * d2);
                if (h % 2 == 0) a *= -.7f;
            }
            amplitudes[h] = a;
        }
        for (int level = 0; level < kLevels; ++level) {
            auto& t = tables_[bank][frame][level];
            for (int h = 1; h <= (128 >> level); ++h) for (int i = 0; i < kSize; ++i)
                t[i] += amplitudes[h] * sine_[(h * i) & (kSize - 1)];
        }
        float peak = 0;
        for (float x : tables_[bank][frame][0]) peak = std::max(peak, std::abs(x));
        const float scale = 1.0f / std::max(peak, .001f);
        // Same normalization for every mip preserves fundamental amplitude.
        for (auto& t : tables_[bank][frame]) { for (auto& x : t) x *= scale; t[kSize] = t[0]; }
    }
}
float WavetableBank::lookup(const Table& t, double phase) noexcept {
    const double pos = wrap(phase) * kSize;
    const auto i = static_cast<int>(pos);
    return lerp(t[i], t[i+1], static_cast<float>(pos - i));
}
float WavetableBank::sine(double phase) const noexcept { return lookup(sine_, phase); }
float WavetableBank::mipFor(float frequency, float sampleRate) noexcept {
    // Fade to the next lower harmonic limit before the richer level aliases.
    return std::clamp(std::log2(std::max(frequency, .001f) * 128.0f / (.42f * sampleRate)) + 1.0f, 0.0f, 7.0f);
}
float WavetableBank::read(int bank, float position, double phase, float mip) const noexcept {
    const float pos = unit(position) * (kFrames - 1);
    const auto f = static_cast<int>(pos), g = std::min(f + 1, kFrames - 1);
    const int level = std::clamp(static_cast<int>(mip), 0, kLevels - 1), next = std::min(level + 1, kLevels - 1);
    const float blend = std::clamp(mip - level, 0.0f, 1.0f);
    const auto& a = tables_[std::clamp(bank, 0, kBanks - 1)][f];
    const auto& b = tables_[std::clamp(bank, 0, kBanks - 1)][g];
    return lerp(lerp(lookup(a[level], phase), lookup(a[next], phase), blend),
                lerp(lookup(b[level], phase), lookup(b[next], phase), blend), pos - f);
}
} // namespace astra::synth
