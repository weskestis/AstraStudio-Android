#include "FxRack.h"
namespace astra::fx {
bool Rack::prepare(double rate) noexcept{
    if(!std::isfinite(rate)||rate<8000||rate>384000)return false;
    rate_=static_cast<float>(rate);smooth_=1-coefficient(5,rate_);controlSmooth_=1-std::pow(1-smooth_,32);
    if(!delay_.prepare(rate_)||!reverb_.prepare(rate_)||!chorus_.prepare(rate_)||!modulation_.prepare(rate_))return false;
    saturator_.prepare(rate_);values_=target_=config_.effective();dirty_=true;update();reset();return true;
}
void Rack::configure(const Config& config) noexcept{
    const bool structural=!config_.structureEquals(config);
    if(structural){tail_=last_;fade_=64;}
    for(int id=0;id<kUnits;++id)if(config_[enableIds[id]]==0&&config[enableIds[id]]!=0)resetUnit(id);
    config_=config;target_=config.effective();dirty_=true;countdown_=0;
    if(structural){eq_.reset();multiband_.reset();sidechain_.reset();modulation_.reset();}
}
void Rack::update() noexcept{
    if(!dirty_)return;
    bool moving=false;
    for(int i=0;i<ParameterCount;++i){if(specs[i].integer)values_[i]=target_[i];else{
        const float difference=target_[i]-values_[i];if(std::abs(difference)<1e-5f)values_[i]=target_[i];else{values_[i]=flush(values_[i]+controlSmooth_*difference);moving=true;}}}
    eq_.set(values_,rate_);multiband_.set(values_,rate_);saturator_.set(values_,rate_);sidechain_.set(values_,rate_);
    delay_.set(values_,rate_,bpm_);reverb_.set(values_,rate_);chorus_.set(values_,rate_);modulation_.set(values_,rate_);dirty_=moving;
}
void Rack::resetUnit(int id) noexcept{
    switch(id){case 0:eq_.reset();break;case 1:multiband_.reset();break;case 2:saturator_.reset();break;case 3:sidechain_.reset();break;
        case 4:delay_.reset();break;case 5:reverb_.reset();break;case 6:chorus_.reset();break;case 7:modulation_.reset();break;}
}
void Rack::reset() noexcept{for(int i=0;i<kUnits;++i)resetUnit(i);dry_.reset();parallelPad_.reset();detectorDelay_.reset();last_=tail_={};fade_=0;}
Stereo Rack::unit(int id,Stereo x,Stereo detector) noexcept{
    if(id==2)return saturator_.tick(x,unitMix_[2]);
    if(unitMix_[id]<1e-6f)return x;
    Stereo wet=x;switch(id){case 0:wet=eq_.tick(x);break;case 1:wet=multiband_.tick(x);break;
        case 3:wet=sidechain_.tick(x,config_[ScSource]<0?x:detector);break;case 4:wet=delay_.tick(x);break;
        case 5:wet=reverb_.tick(x);break;case 6:wet=chorus_.tick(x);break;case 7:wet=modulation_.tick(x);break;default:break;}
    return blend(x,wet,unitMix_[id]);
}
Stereo Rack::tick(Stereo input,Stereo externalDetector) noexcept{
    if(countdown_--<=0){update();countdown_=31;}
    for(int i=0;i<kUnits;++i){const float target=config_[enableIds[i]];unitMix_[i]+=smooth_*(target-unitMix_[i]);if(std::abs(target-unitMix_[i])<1e-6f)unitMix_[i]=target;}
    const auto dry=dry_.tick(input),lateDetector=detectorDelay_.tick(externalDetector);Stereo wet{};
    if(config_[RackMode]==0){wet=input;bool delayed=false;for(int id:config_.order){wet=unit(id,wet,delayed?lateDetector:externalDetector);if(id==2)delayed=true;}}
    else{
        std::array<Stereo,2> lanes{input,input};std::array<bool,2> delayed{};
        for(int id:config_.order){const int lane=config_.lane[id];lanes[lane]=unit(id,lanes[lane],delayed[lane]?lateDetector:externalDetector);if(id==2)delayed[lane]=true;}
        const int pad=delayed[0]?1:0;lanes[pad]=parallelPad_.tick(lanes[pad]);wet=blend(lanes[0],lanes[1],values_[RackBalance]);
    }
    Stereo out=blend(dry,wet,values_[RackMix])*dbGain(values_[RackOutput]);
    if(!std::isfinite(out.l)||!std::isfinite(out.r))out={};
    out.l=std::clamp(out.l,-64.0f,64.0f);out.r=std::clamp(out.r,-64.0f,64.0f);
    if(fade_>0){out=blend(out,tail_,static_cast<float>(fade_--)/64);}
    last_=out;return out;
}
} // namespace astra::fx
