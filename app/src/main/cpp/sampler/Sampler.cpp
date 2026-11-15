#include "Sampler.h"
#include "project/Project.h"
#include <algorithm>
#include <cmath>
#include <numbers>
namespace astra::sampler {
Kernel::Kernel()noexcept {
    constexpr double pi=std::numbers::pi;
    for(int b=0;b<bands;++b){const double cutoff=std::exp2(-b/8.0);
        for(int p=0;p<phases;++p){double total=0;
            for(int t=0;t<taps;++t){const double x=t-7-p/static_cast<double>(phases),v=x*cutoff*pi;
                const double sinc=std::abs(v)<1e-12?1:std::sin(v)/v;
                const float value=static_cast<float>(cutoff*sinc*(.5+.5*std::cos(pi*x/8)));
                table_[(b*phases+p)*taps+t]=value;total+=value;}
            for(int t=0;t<taps;++t)table_[(b*phases+p)*taps+t]/=static_cast<float>(total);
        }
    }
}
void Voice::start(const project::Sample* sample,const Zone& z,int note,float velocity,double outputRate,bool drum)noexcept {
    silence();if(!sample||z.first<0||z.last<=z.first||z.last>sample->frames()||!std::isfinite(outputRate)||outputRate<=0)return;
    sample_=sample;zone_=z;rate_=outputRate;held_=true;active_=true;stage_=0;stageFrames_=age_=0;velocity_=velocity;
    step_=sample->rate/outputRate*std::exp2(((drum?0:note-z.root)+z.pitch)/12.0);
    band_=std::clamp(static_cast<int>(std::ceil(std::log2(std::max(1.0,step_))*8)),0,Kernel::bands-1);
    position_=z.reverse?z.last-1.0:static_cast<double>(z.first);
    attackFrames_=std::max(1,static_cast<int>(z.attack*outputRate));decayFrames_=std::max(1,static_cast<int>(z.decay*outputRate));
    left_=z.gain*std::sqrt(1-std::max(0.f,z.pan));right_=z.gain*std::sqrt(1+std::min(0.f,z.pan));
}
void Voice::release(bool force)noexcept {
    if(!active_||stage_==3||(!force&&zone_.oneShot))return;
    held_=false;stage_=3;releaseStep_=env_/std::max(1.f,zone_.release*static_cast<float>(rate_));
    if(env_<=0)silence();
}
void Voice::choke()noexcept {
    if(!active_)return;
    held_=false;stage_=3;releaseStep_=env_/std::max(1.,.005*rate_);
    if(env_<=0)silence();
}
fx::Stereo Voice::tick(const Kernel& kernel)noexcept {
    if(!active_||!sample_)return {};
    if(position_<zone_.first||position_>=zone_.last){silence();return {};}
    if(stage_==0){env_=static_cast<float>(++stageFrames_)/attackFrames_;if(stageFrames_>=attackFrames_){stage_=1;stageFrames_=0;env_=1;}}
    else if(stage_==1){env_=1+(zone_.sustain-1)*static_cast<float>(++stageFrames_)/decayFrames_;if(stageFrames_>=decayFrames_){stage_=2;env_=zone_.sustain;}}
    else if(stage_==3){env_=std::max(0.f,env_-releaseStep_);if(env_<=1e-7f){silence();return {};}}
    if(stage_==2&&env_<=1e-7f){silence();return {};}
    const auto base=static_cast<int64_t>(position_);const double fraction=position_-base;fx::Stereo value{};
    if(step_==1&&fraction==0){value={sample_->stereo[base*2],sample_->stereo[base*2+1]};}
    else {const auto* weights=kernel.at(band_,std::clamp(static_cast<int>(fraction*Kernel::phases),0,Kernel::phases-1));
        for(int t=0;t<Kernel::taps;++t){const auto at=std::clamp(base+t-7,zone_.first,zone_.last-1)*2;
            value.l+=sample_->stereo[at]*weights[t];value.r+=sample_->stereo[at+1]*weights[t];}}
    const bool looping=zone_.loop&&held_;
    const double remaining=(zone_.reverse?position_-zone_.first+1:zone_.last-position_)/step_;
    if(age_<32)++age_;
    float fade=static_cast<float>(age_)/32;
    if(!looping)fade*=std::min(1.f,static_cast<float>(remaining)/32);
    else if(position_>=zone_.loopFirst&&position_<zone_.loopLast){
        const double edge=std::min(position_-zone_.loopFirst+1,zone_.loopLast-position_);
        fade*=std::min(1.f,static_cast<float>(edge/std::min(32.0,(zone_.loopLast-zone_.loopFirst)/4.0)));}
    value.l*=env_*velocity_*left_*fade;value.r*=env_*velocity_*right_*fade;
    position_+=zone_.reverse?-step_:step_;
    if(looping){const double span=static_cast<double>(zone_.loopLast-zone_.loopFirst);
        if(!zone_.reverse&&position_>=zone_.loopLast)position_=zone_.loopFirst+std::fmod(position_-zone_.loopFirst,span);
        if(zone_.reverse&&position_<zone_.loopFirst){const double remainder=std::fmod(zone_.loopFirst-position_,span);position_=remainder==0?zone_.loopFirst:zone_.loopLast-remainder;}}
    return value;
}
}
