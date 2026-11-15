#pragma once
#include "ParameterIds.h"
#include <array>
#include <span>

namespace astra::synth {
inline constexpr int kLfos = 4;
inline constexpr int kShapePoints = 32;
inline constexpr int kModSlots = 16;
inline constexpr int kWireCount = ParameterCount + kLfos * kShapePoints + kModSlots * 3;
enum class Source : int { Off, Lfo1, Lfo2, Lfo3, Lfo4, Env1, Env2, Env3, Velocity, Key };
enum class Target : int {
    PositionA, PositionB, FmAmount, Pitch, Cutoff1, Cutoff2, Amplitude,
    WarpA, WarpB, Resonance1, Resonance2, FmIndex, Drive1, Drive2, LevelA, LevelB, Count
};
struct Route { int source{}; int target{}; float amount{}; };
struct Patch {
    std::array<float, ParameterCount> values{};
    std::array<std::array<float, kShapePoints>, kLfos> shapes{};
    std::array<Route, kModSlots> routes{};
    Patch() noexcept;
    float operator[](int parameter) const noexcept { return values[static_cast<std::size_t>(parameter)]; }
    float& operator[](int parameter) noexcept { return values[static_cast<std::size_t>(parameter)]; }
    bool valid() const noexcept;
    bool decode(std::span<const float> wire) noexcept;
    void encode(std::span<float, kWireCount> wire) const noexcept;
};
Patch preset(int index) noexcept;
} // namespace astra::synth
