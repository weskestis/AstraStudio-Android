#pragma once
#include "FxIds.h"
#include <array>
#include <span>
namespace astra::fx {
inline constexpr int kUnits=8, kMacros=4, kWireCount=ParameterCount+28;
inline constexpr std::array<int,kUnits> enableIds{EqOn,OttOn,ShaperOn,ScOn,DelayOn,ReverbOn,ChorusOn,ModOn};
inline constexpr std::array<int,16> macroTargets{ShaperPre,DelayMix,ReverbMix,ChorusDepth,OttDepth,ScThreshold,ModDepth,
    Eq1Gain,Eq2Gain,Eq3Gain,Eq4Gain,Eq5Gain,Eq6Gain,Eq7Gain,Eq8Gain,RackOutput};
struct Macro { int target{}; float depth{}, value{}; };
struct Config {
    std::array<float,ParameterCount> values{};
    std::array<int,kUnits> order{0,1,2,3,4,5,6,7};
    std::array<int,kUnits> lane{};
    std::array<Macro,kMacros> macros{};
    Config() noexcept;
    float operator[](int i) const noexcept { return values[static_cast<std::size_t>(i)]; }
    float& operator[](int i) noexcept { return values[static_cast<std::size_t>(i)]; }
    bool valid() const noexcept;
    bool decode(std::span<const float> wire) noexcept;
    void encode(std::span<float,kWireCount> wire) const noexcept;
    std::array<float,ParameterCount> effective() const noexcept;
    bool structureEquals(const Config& other) const noexcept;
};
Config preset(int index) noexcept;
} // namespace astra::fx
