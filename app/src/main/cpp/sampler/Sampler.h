#pragma once
#include "fx/FxPrimitives.h"
#include <array>
#include <cstdint>
namespace astra::project { struct Sample; }
namespace astra::sampler {
struct Zone {
    int asset{-1};int64_t first{},last{},loopFirst{},loopLast{};int root{60};
    float pitch{},gain{1},pan{},attack{.002f},decay{.3f},sustain{1},release{.05f};
    bool reverse{},loop{},oneShot{};int choke{};
};
struct Instrument {int mode{};Zone sample;std::array<Zone,16> pads;};
// Prepared on the control thread. Conservative cutoff buckets suppress aliasing
// when transposing up. 64-tap Blackman-Harris sinc with interpolated phase coefficients.
class Kernel {
public:
    static constexpr int taps=64,phases=128,bands=129;
    Kernel() noexcept;
    const float* at(int band,int phase)const noexcept{return table_.data()+(band*(phases+1)+phase)*taps;}
    static int bandFor(double step)noexcept;
    fx::Stereo read(const float* stereo,double position,int64_t first,int64_t last,int band,bool exactRate)const noexcept;
private:std::array<float,bands*(phases+1)*taps> table_{};
};
class Voice {
public:
    void start(const project::Sample*,const Zone&,int note,float velocity,double outputRate,bool drum)noexcept;
    void release(bool force=false)noexcept;
    void choke()noexcept;
    void silence()noexcept{active_=false;sample_=nullptr;env_=0;last_={};tail_={};tailFrames_=0;}
    fx::Stereo tick(const Kernel&)noexcept;
    bool active()const noexcept{return active_;}
    bool releasing()const noexcept{return stage_==3;}
    float amplitude()const noexcept{return env_*velocity_;}
    int group()const noexcept{return zone_.choke;}
    int asset()const noexcept{return zone_.asset;}
    const project::Sample* source()const noexcept{return sample_;}
private:
    fx::Stereo last_{},tail_{};int tailFrames_{};
    const project::Sample* sample_{};Zone zone_;double position_{},step_{},rate_{48000};
    float env_{},velocity_{},left_{},right_{},releaseStep_{};
    int stage_{},age_{},attackFrames_{},decayFrames_{},stageFrames_{},band_{};
    bool active_{},held_{};
};
}
