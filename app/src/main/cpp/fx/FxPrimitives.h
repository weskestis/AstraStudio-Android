#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <new>
#include <numbers>
namespace astra::fx {
struct Stereo { float l{},r{}; };
inline Stereo operator+(Stereo a,Stereo b) noexcept{return {a.l+b.l,a.r+b.r};}
inline Stereo operator-(Stereo a,Stereo b) noexcept{return {a.l-b.l,a.r-b.r};}
inline Stereo operator*(Stereo a,float b) noexcept{return {a.l*b,a.r*b};}
inline float peak(Stereo a) noexcept{return std::max(std::abs(a.l),std::abs(a.r));}
inline float flush(float x) noexcept{return std::abs(x)<1e-18f?0:x;}
inline float dbGain(float db) noexcept{return std::exp2(db/6.020599913f);}
inline float gainDb(float gain) noexcept{return 20*std::log10(std::max(gain,1e-9f));}
inline Stereo blend(Stereo a,Stereo b,float t) noexcept{return a+(b-a)*t;}
inline float coefficient(float ms,float rate) noexcept{return std::exp(-1.0f/std::max(1.0f,ms*.001f*rate));}

// Dynamic capacity is obtained only by prepare on the control thread. Reset is
// constant-time: validity counts prevent reads of old/uninitialized storage.
class Delay {
public:
    bool prepare(int capacity) noexcept {
        if(capacity<4)return false;
        if(capacity>capacity_){std::unique_ptr<float[]> next(new(std::nothrow) float[capacity]);if(!next)return false;data_=std::move(next);capacity_=capacity;}
        reset();return true;
    }
    void reset() noexcept{write_=filled_=0;}
    float read(float samples) const noexcept {
        if(capacity_==0)return 0;
        samples=std::clamp(samples,1.0f,static_cast<float>(capacity_-2));
        if(static_cast<float>(filled_)<std::ceil(samples))return 0;
        // Wrap integer indices, not floating positions: a tiny negative float
        // plus a large capacity can round up to capacity (one past the end).
        const int whole=static_cast<int>(samples);const float fraction=samples-whole;
        int a=write_-whole;if(a<0)a+=capacity_;
        if(fraction==0)return data_[a]; // The older neighbor may not be filled yet.
        const int b=a==0?capacity_-1:a-1;
        return data_[a]+(data_[b]-data_[a])*fraction;
    }
    void push(float x) noexcept {data_[write_]=flush(x);if(++write_==capacity_)write_=0;if(filled_<capacity_)++filled_;}
private:
    std::unique_ptr<float[]> data_;int capacity_{},write_{},filled_{};
};
template<int N> class FixedDelay {
public:
    Stereo tick(Stereo x) noexcept{const auto y=data_[write_];data_[write_]=x;if(++write_==N)write_=0;return y;}
    void reset() noexcept{data_.fill({});write_=0;}
private:std::array<Stereo,N> data_{};int write_{};
};
struct Coefficients { double b0{1},b1{},b2{},a1{},a2{}; };
Coefficients biquad(int type,float frequency,float q,float gain,float rate) noexcept;
class Biquad {
public:
    void set(Coefficients next,int samples=32) noexcept {
        if(samples<=0){c_=next;remaining_=0;return;}
        target_=next;remaining_=samples;
        step_={(next.b0-c_.b0)/samples,(next.b1-c_.b1)/samples,(next.b2-c_.b2)/samples,(next.a1-c_.a1)/samples,(next.a2-c_.a2)/samples};
    }
    Stereo tick(Stereo x) noexcept {
        if(remaining_>0){c_.b0+=step_.b0;c_.b1+=step_.b1;c_.b2+=step_.b2;c_.a1+=step_.a1;c_.a2+=step_.a2;if(--remaining_==0)c_=target_;}
        Stereo out{};float* y[2]={&out.l,&out.r};const float input[2]={x.l,x.r};
        for(int ch=0;ch<2;++ch){const double out=c_.b0*input[ch]+z1_[ch];
            z1_[ch]=c_.b1*input[ch]-c_.a1*out+z2_[ch];z2_[ch]=c_.b2*input[ch]-c_.a2*out;
            if(std::abs(z1_[ch])<1e-24)z1_[ch]=0;
            if(std::abs(z2_[ch])<1e-24)z2_[ch]=0;
            *y[ch]=static_cast<float>(out);}
        return out;
    }
    void reset() noexcept{z1_.fill(0);z2_.fill(0);}
private:Coefficients c_{},target_{},step_{};std::array<double,2> z1_{},z2_{};int remaining_{};
};
class DcBlock {
public:
    void prepare(float rate) noexcept{pole_=std::exp(-2*std::numbers::pi_v<float>*8/rate);reset();}
    Stereo tick(Stereo x) noexcept{Stereo y=x-lastIn_+lastOut_*pole_;lastIn_=x;lastOut_={flush(y.l),flush(y.r)};return y;}
    void reset() noexcept{lastIn_=lastOut_={};}
private:Stereo lastIn_{},lastOut_{};float pole_{.999f};
};
} // namespace astra::fx
