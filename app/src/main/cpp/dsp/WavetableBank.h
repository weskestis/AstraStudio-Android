#pragma once
#include <array>
namespace astra::synth {
class WavetableBank {
public:
    static constexpr int kSize = 1024, kLevels = 8, kFrames = 4, kBanks = 4;
    WavetableBank() noexcept; // Control thread only; never build tables in render.
    float read(int bank, float position, double phase, float mip) const noexcept;
    float sine(double phase) const noexcept;
    static float mipFor(float frequency, float sampleRate) noexcept;
private:
    using Table = std::array<float, kSize + 1>;
    std::array<std::array<std::array<Table, kLevels>, kFrames>, kBanks> tables_{};
    Table sine_{};
    static float lookup(const Table& table, double phase) noexcept;
};
} // namespace astra::synth
