#pragma once
#include <algorithm>
#include <cmath>
namespace astra::synth {
inline double wrap(double phase) noexcept { return phase - std::floor(phase); }
inline float softClip(float x) noexcept { return x / std::sqrt(1.0f + x * x); }
inline float flush(float x) noexcept { return std::abs(x) < 1e-18f ? 0.0f : x; }
inline float lerp(float a, float b, float t) noexcept { return a + (b - a) * t; }
inline float unit(float v) noexcept { return std::clamp(v, 0.0f, 1.0f); }
} // namespace astra::synth
