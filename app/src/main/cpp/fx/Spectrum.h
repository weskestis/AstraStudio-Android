#pragma once
#include "core/SpscRing.h"
#include "FxPrimitives.h"
#include <atomic>
#include <complex>
namespace astra::fx {
class Spectrum {
public:
    static constexpr int kSize=2048,kBands=64;
    Spectrum() noexcept;
    // One audio producer. Control thread exclusively owns analyze/reset.
    void push(Stereo sample,int track) noexcept;
    std::array<float,kBands> analyze(float sampleRate) noexcept;
    int track() const noexcept{return track_;}
    uint32_t dropped() const noexcept{return dropped_.load(std::memory_order_relaxed);}
private:
    struct Sample { Stereo audio;int track; };
    SpscRing<Sample,32768> queue_;
    std::atomic<uint32_t> dropped_{0};
    std::array<Stereo,kSize> history_{};
    std::array<float,kSize> window_{};
    std::array<std::complex<float>,kSize> work_{};
    std::array<float,kSize/2+1> power_{};
    std::array<float,kBands> display_{};
    int at_{},filled_{},track_{-1};float windowSum_{};
    void fft() noexcept;
};
} // namespace astra::fx
