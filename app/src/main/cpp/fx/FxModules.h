#pragma once
#include "FxConfig.h"
#include "FxPrimitives.h"
#include "dsp/HalfBand.h"
namespace astra::fx {
using Values=std::array<float,ParameterCount>;
class Equalizer {
public:void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x) noexcept;void reset() noexcept;
private:std::array<Biquad,8> bands_{};std::array<float,8> enabled_{},target_{};std::array<int,8> channel_{};bool ms_{};float smooth_{.004f};
};
class Multiband {
public:void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x) noexcept;void reset() noexcept;std::array<float,3> meters() const noexcept{return meters_;}
private:
    struct Split { std::array<Biquad,2> lp,hp;void set(float hz,float rate) noexcept;std::array<Stereo,2> tick(Stereo x) noexcept;void reset() noexcept; };
    Split low_,high_,compensate_;
    std::array<float,3> env_{},gain_{1,1,1},targetGain_{1,1,1},lower_{},upper_{},makeup_{},meters_{};
    float attack_{},release_{},up_{1},down_{1},upRatio_{4},downRatio_{8},depth_{.6f};int counter_{};
};
class Sidechain {
public:void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x,Stereo detector) noexcept;void reset() noexcept;float reduction() const noexcept{return std::max(0.0f,-gainDb(gain_));}
    static float curve(float level,float threshold,float ratio,float knee) noexcept;
private:Biquad hp_;float env_{},gain_{1},targetGain_{1},threshold_{-28},ratio_{6},knee_{6},attack_{},release_{},makeup_{1},mix_{1};int counter_{};
};
class Saturator {
public:void prepare(float rate) noexcept{dc_.prepare(rate);reset();}
    void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x,float enabled) noexcept;void reset() noexcept;
    static float shape(float x,int mode) noexcept;
private:
    struct Fir {std::array<float,32> data{};int write{};float tick(float x) noexcept;void reset() noexcept{data.fill(0);write=0;}};
    std::array<Fir,2> up_{},down_{};FixedDelay<15> dry_;DcBlock dc_;float pre_{1},post_{1},mix_{1};int mode_{};
};
class PingPong {
public:bool prepare(float rate) noexcept;void set(const Values& p,float rate,float bpm) noexcept;Stereo tick(Stereo x) noexcept;void reset() noexcept;
private:std::array<Delay,2> delay_;Stereo tone_{};float samples_{100},targetSamples_{100},feedback_{.35f},toneCoefficient_{.5f},mix_{.25f},slew_{.001f};
};
class Reverb {
public:bool prepare(float rate) noexcept;void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x) noexcept;void reset() noexcept;
private:std::array<Delay,8> lines_;std::array<Delay,4> diffusion_;std::array<float,8> times_{},targetTimes_{},feedback_{},damped_{};
    std::array<float,4> diffuseTimes_{};float damping_{.5f},wash_{.6f},mix_{.2f},freeze_{},freezeTarget_{},slew_{.001f};
};
class Chorus {
public:bool prepare(float rate) noexcept;void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x) noexcept;void reset() noexcept;
private:std::array<Delay,2> delay_;Stereo last_{};double phase_{},step_{};float rate_{48000},depth_{.5f},feedback_{.1f},mix_{.35f};
};
class ModulationFx {
public:bool prepare(float rate) noexcept;void set(const Values& p,float rate) noexcept;Stereo tick(Stereo x) noexcept;void reset() noexcept;
private:std::array<Delay,2> delay_;std::array<std::array<float,6>,2> allpass_{};Stereo last_{};double phase_{},step_{};
    float rate_{48000},depth_{.6f},feedback_{.25f},mix_{.5f};int mode_{};
};
class LookaheadLimiter {
public:bool prepare(double rate) noexcept;void setRelease(float milliseconds) noexcept;void reset() noexcept;Stereo tick(Stereo x) noexcept;
    int latency() const noexcept{return lookahead_;}float reduction() const noexcept{return std::max(0.0f,-gainDb(gain_));}
    static constexpr float ceiling=.988553095f;
private:static constexpr int kCapacity=4096;std::array<Stereo,kCapacity> audio_{};std::array<float,kCapacity> peaks_{};std::array<uint64_t,kCapacity> ages_{};
    uint64_t frame_{};int head_{},tail_{},lookahead_{240};float rate_{48000},gain_{1},release_{.999f};
};
} // namespace astra::fx
