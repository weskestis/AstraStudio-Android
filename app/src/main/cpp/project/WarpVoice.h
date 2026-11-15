#pragma once
#include "fx/FxPrimitives.h"
#include <memory>
namespace astra::sampler { class Kernel; }
namespace astra::project {
struct Clip;struct Sample;struct TempoMap;
// One bounded, preallocated stereo processor per track. Ownership and FFT setup
// stay on prepare(); start()/tick() are allocation-free render-thread operations.
class WarpVoice {
public:
    WarpVoice() noexcept;
    ~WarpVoice();
    bool prepare(double rate,const sampler::Kernel* kernel) noexcept;
    void start(const Clip&,const Sample&,double beat,double phase,double offset,double bpm,const TempoMap*) noexcept;
    fx::Stereo tick(const Sample&,const TempoMap*) noexcept;
private:
    struct State;std::unique_ptr<State> state_;
};
}
