#include "FxModules.h"
namespace astra::fx {
namespace {constexpr float kPi=std::numbers::pi_v<float>;constexpr float kRootHalf=.7071067811865475f;
float bounded(float x) noexcept{return std::isfinite(x)?std::clamp(x,-64.0f,64.0f):0.0f;}}
void Equalizer::set(const Values& p,float rate) noexcept{
    ms_=p[EqMs]!=0;smooth_=1-coefficient(5,rate);
    for(int i=0;i<8;++i){const int base=Eq1On+i*6;target_[i]=p[base];channel_[i]=static_cast<int>(p[base+5]);
        bands_[i].set(biquad(static_cast<int>(p[base+1]),p[base+2],p[base+4],p[base+3],rate));}
}
Stereo Equalizer::tick(Stereo x) noexcept{
    if(ms_)x={(x.l+x.r)*kRootHalf,(x.l-x.r)*kRootHalf};
    for(int i=0;i<8;++i){enabled_[i]+=smooth_*(target_[i]-enabled_[i]);const auto y=bands_[i].tick(x);
        const float l=ms_&&channel_[i]==2?0:enabled_[i],r=ms_&&channel_[i]==1?0:enabled_[i];
        x={x.l+(y.l-x.l)*l,x.r+(y.r-x.r)*r};}
    return ms_?Stereo{(x.l+x.r)*kRootHalf,(x.l-x.r)*kRootHalf}:x;
}
void Equalizer::reset() noexcept{for(auto& b:bands_)b.reset();}
void Multiband::Split::set(float hz,float rate) noexcept{for(auto& b:lp)b.set(biquad(3,hz,kRootHalf,0,rate));for(auto& b:hp)b.set(biquad(4,hz,kRootHalf,0,rate));}
std::array<Stereo,2> Multiband::Split::tick(Stereo x) noexcept{return {lp[1].tick(lp[0].tick(x)),hp[1].tick(hp[0].tick(x))};}
void Multiband::Split::reset() noexcept{for(auto& b:lp)b.reset();for(auto& b:hp)b.reset();}
void Multiband::set(const Values& p,float rate) noexcept{
    low_.set(p[OttLow],rate);high_.set(p[OttHigh],rate);compensate_.set(p[OttHigh],rate);
    attack_=coefficient(p[OttAttack],rate);release_=coefficient(p[OttRelease],rate);up_=p[OttUp];down_=p[OttDown];
    upRatio_=p[OttUpRatio];downRatio_=p[OttDownRatio];depth_=p[OttDepth];
    for(int i=0;i<3;++i){lower_[i]=p[Ott1Lower+i*3];upper_[i]=p[Ott1Upper+i*3];makeup_[i]=p[Ott1Gain+i*3];}
}
Stereo Multiband::tick(Stereo x) noexcept{
    const auto first=low_.tick(x),rest=high_.tick(first[1]),aligned=compensate_.tick(first[0]);
    const std::array<Stereo,3> bands{aligned[0]+aligned[1],rest[0],rest[1]};Stereo out{};
    for(int i=0;i<3;++i){const float amplitude=peak(bands[i]);const float c=amplitude>env_[i]?attack_:release_;env_[i]=flush(amplitude+c*(env_[i]-amplitude));
        if(counter_==0){const float level=gainDb(env_[i]);
            const float gate=std::clamp((level+84.0f)/12.0f,0.0f,1.0f);
            const float upward=std::min(18.0f,std::max(0.0f,lower_[i]-level)*(1-1/upRatio_))*up_*gate;
            const float downward=std::max(0.0f,level-upper_[i])*(1-1/downRatio_)*down_;
            targetGain_[i]=dbGain(std::clamp(upward-downward+makeup_[i],-60.0f,24.0f));}
        gain_[i]+=.125f*(targetGain_[i]-gain_[i]);const float g=1+depth_*(gain_[i]-1);out=out+bands[i]*g;meters_[i]=gainDb(g);
    }
    counter_=(counter_+1)&7;return out;
}
void Multiband::reset() noexcept{low_.reset();high_.reset();compensate_.reset();env_.fill(0);gain_.fill(1);targetGain_.fill(1);meters_.fill(0);counter_=0;}
float Sidechain::curve(float level,float threshold,float ratio,float knee) noexcept{
    const float over=level-threshold,slope=1-1/ratio;
    if(knee<=0)return std::max(0.0f,over)*slope;
    if(over<=-knee*.5f)return 0;
    if(over>=knee*.5f)return over*slope;
    const float t=over+knee*.5f;return slope*t*t/(2*knee);
}
void Sidechain::set(const Values& p,float rate) noexcept{
    hp_.set(biquad(4,p[ScHighpass],kRootHalf,0,rate));threshold_=p[ScThreshold];ratio_=p[ScRatio];knee_=p[ScKnee];
    attack_=coefficient(p[ScAttack],rate);release_=coefficient(p[ScRelease],rate);makeup_=dbGain(p[ScMakeup]);mix_=p[ScMix];
}
Stereo Sidechain::tick(Stereo x,Stereo detector) noexcept{
    const float amplitude=peak(hp_.tick(detector));const float c=amplitude>env_?attack_:release_;env_=flush(amplitude+c*(env_-amplitude));
    if(counter_==0)targetGain_=dbGain(-std::min(72.0f,curve(gainDb(env_),threshold_,ratio_,knee_)));
    counter_=(counter_+1)&7;gain_+=.125f*(targetGain_-gain_);return blend(x,x*(gain_*makeup_),mix_);
}
void Sidechain::reset() noexcept{hp_.reset();env_=0;gain_=targetGain_=1;counter_=0;}
float Saturator::Fir::tick(float x) noexcept{
    data[write]=x;float out=0;for(int i=0;i<31;i+=2)out+=synth::halfBand[i]*data[(write-i)&31];out+=synth::halfBand[15]*data[(write-15)&31];write=(write+1)&31;return out;
}
float Saturator::shape(float x,int mode) noexcept{
    if(mode==1)return std::clamp(x,-1.0f,1.0f);
    if(mode==2)return std::sin(x);
    if(mode==3)return std::tanh(x+.2f)-std::tanh(.2f);
    return std::tanh(x);
}
void Saturator::set(const Values& p,float) noexcept{pre_=dbGain(p[ShaperPre]);post_=dbGain(p[ShaperPost]);mix_=p[ShaperMix];mode_=static_cast<int>(p[ShaperMode]);}
Stereo Saturator::tick(Stereo x,float enabled) noexcept{
    const auto dry=dry_.tick(x);if(enabled<1e-6f)return dry;
    Stereo wet{};
    for(int ch=0;ch<2;++ch){const float input=(ch==0?x.l:x.r)*pre_;float decimated=0;
        for(int s=0;s<2;++s){const float up=up_[ch].tick(s==0?input*2:0);const float y=down_[ch].tick(shape(up,mode_));if(s==0)decimated=y;}
        (ch==0?wet.l:wet.r)=decimated*post_;}
    return blend(dry,dc_.tick(wet),mix_*enabled);
}
void Saturator::reset() noexcept{for(auto& f:up_)f.reset();for(auto& f:down_)f.reset();dry_.reset();dc_.reset();}
bool PingPong::prepare(float rate) noexcept{for(auto& d:delay_)if(!d.prepare(static_cast<int>(std::ceil(rate*4))+4))return false;reset();return true;}
void PingPong::set(const Values& p,float rate,float bpm) noexcept{
    targetSamples_=rate*(p[DelaySync]!=0?60.0f/std::max(30.0f,bpm)*p[DelayBeats]:p[DelayMs]*.001f);
    targetSamples_=std::clamp(targetSamples_,1.0f,rate*4);feedback_=p[DelayFeedback];mix_=p[DelayMix];
    toneCoefficient_=1-std::exp(-2*kPi*std::min(p[DelayTone],rate*.4f)/rate);slew_=1-coefficient(30,rate);
}
Stereo PingPong::tick(Stereo x) noexcept{
    samples_+=slew_*(targetSamples_-samples_);const Stereo wet{delay_[0].read(samples_),delay_[1].read(samples_)};
    tone_=tone_+(wet-tone_)*toneCoefficient_;delay_[0].push(bounded((x.l+x.r)*.5f+tone_.r*feedback_));delay_[1].push(bounded(tone_.l*feedback_));return blend(x,wet,mix_);
}
void PingPong::reset() noexcept{for(auto& d:delay_)d.reset();tone_={};samples_=targetSamples_;}
bool Reverb::prepare(float rate) noexcept{
    for(auto& d:lines_)if(!d.prepare(static_cast<int>(std::ceil(rate*.13f))+4))return false;
    for(auto& d:diffusion_)if(!d.prepare(static_cast<int>(std::ceil(rate*.02f))+4))return false;
    constexpr float times[4]={.0051f,.0073f,.0117f,.0139f};for(int i=0;i<4;++i)diffuseTimes_[i]=std::round(times[i]*rate);
    reset();return true;
}
void Reverb::set(const Values& p,float rate) noexcept{
    constexpr float seconds[8]={.0297f,.0371f,.0411f,.0437f,.0531f,.0617f,.0713f,.0797f};
    for(int i=0;i<8;++i){targetTimes_[i]=std::max(1.0f,std::round(seconds[i]*(.5f+p[ReverbSize])*rate));feedback_[i]=std::pow(.001f,targetTimes_[i]/(rate*p[ReverbDecay]));}
    damping_=1-std::exp(-2*kPi*(16000.0f*std::exp2(-4*p[ReverbDamping]))/rate);wash_=.3f+.4f*p[ReverbWash];mix_=p[ReverbMix];freezeTarget_=p[ReverbFreeze];slew_=1-coefficient(30,rate);
}
Stereo Reverb::tick(Stereo x) noexcept{
    freeze_+=slew_*(freezeTarget_-freeze_);if(std::abs(freezeTarget_-freeze_)<1e-5f)freeze_=freezeTarget_;float diffuse=(x.l+x.r)*.35f;
    for(int i=0;i<4;++i){const float delayed=diffusion_[i].read(diffuseTimes_[i]);const float v=diffuse+wash_*delayed;diffusion_[i].push(v);diffuse=delayed-wash_*v;}
    std::array<float,8> y{};
    for(int i=0;i<8;++i){times_[i]+=slew_*(targetTimes_[i]-times_[i]);if(std::abs(targetTimes_[i]-times_[i])<.01f)times_[i]=targetTimes_[i];const float v=lines_[i].read(times_[i]);const float damp=damping_+(1-damping_)*freeze_;damped_[i]=flush(damped_[i]+damp*(v-damped_[i]));y[i]=damped_[i];}
    const Stereo wet{(y[0]+y[2]-y[4]+y[6])*.4f,(y[1]-y[3]+y[5]+y[7])*.4f};
    for(int width=1;width<8;width*=2)for(int i=0;i<8;i+=width*2)for(int j=0;j<width;++j){const float a=y[i+j],b=y[i+j+width];y[i+j]=a+b;y[i+j+width]=a-b;}
    for(int i=0;i<8;++i){const float g=feedback_[i]+(1-feedback_[i])*freeze_;const float input=(diffuse+((i&1)?x.r:x.l)*.12f)*(1-freeze_);lines_[i].push(bounded(y[i]*.353553391f*g+input));}
    return blend(x,wet,mix_);
}
void Reverb::reset() noexcept{for(auto& d:lines_)d.reset();for(auto& d:diffusion_)d.reset();damped_.fill(0);times_=targetTimes_;freeze_=freezeTarget_;}
bool Chorus::prepare(float rate) noexcept{for(auto& d:delay_)if(!d.prepare(static_cast<int>(std::ceil(rate*.06f))+4))return false;reset();return true;}
void Chorus::set(const Values& p,float rate) noexcept{rate_=rate;step_=p[ChorusRate]/rate;depth_=p[ChorusDepth];feedback_=p[ChorusFeedback];mix_=p[ChorusMix];}
Stereo Chorus::tick(Stereo x) noexcept{
    Stereo wet{};for(int ch=0;ch<2;++ch){float sum=0;for(int voice=0;voice<3;++voice){const float lfo=std::sin(static_cast<float>(phase_)*2*kPi+voice*2*kPi/3+ch*kPi*.5f);sum+=delay_[ch].read(rate_*(.012f+voice*.004f+depth_*.006f*lfo));}(ch==0?wet.l:wet.r)=sum/3;}
    delay_[0].push(bounded(x.l+last_.l*feedback_));delay_[1].push(bounded(x.r+last_.r*feedback_));last_=wet;phase_+=step_;if(phase_>=1)phase_-=1;return blend(x,wet,mix_);
}
void Chorus::reset() noexcept{for(auto& d:delay_)d.reset();last_={};phase_=0;}
bool ModulationFx::prepare(float rate) noexcept{for(auto& d:delay_)if(!d.prepare(static_cast<int>(std::ceil(rate*.01f))+4))return false;reset();return true;}
void ModulationFx::set(const Values& p,float rate) noexcept{rate_=rate;step_=p[ModRate]/rate;depth_=p[ModDepth];feedback_=p[ModFeedback];mix_=p[ModMix];mode_=static_cast<int>(p[ModMode]);}
Stereo ModulationFx::tick(Stereo x) noexcept{
    Stereo wet{};for(int ch=0;ch<2;++ch){const float lfo=.5f+.5f*std::sin(static_cast<float>(phase_)*2*kPi+ch*kPi*.5f);float y=bounded((ch==0?x.l:x.r)+(ch==0?last_.l:last_.r)*feedback_);
        if(mode_==1){const float d=rate_*(.0003f+.004f*depth_*lfo);const float delayed=delay_[ch].read(d);delay_[ch].push(y);y=delayed;}
        else{const float hz=std::min(rate_*.4f,200.0f*std::exp2(4.5f*depth_*lfo));const float g=std::tan(kPi*hz/rate_);const float a=(g-1)/(g+1);
            for(auto& state:allpass_[ch]){const float next=a*y+state;state=flush(y-a*next);y=next;}}
        (ch==0?wet.l:wet.r)=y;}
    last_=wet;phase_+=step_;if(phase_>=1)phase_-=1;return blend(x,wet,mix_);
}
void ModulationFx::reset() noexcept{for(auto& d:delay_)d.reset();for(auto& a:allpass_)a.fill(0);last_={};phase_=0;}
bool LookaheadLimiter::prepare(double rate) noexcept{
    if(!std::isfinite(rate)||rate<8000||rate>384000)return false;
    rate_=static_cast<float>(rate);lookahead_=static_cast<int>(std::ceil(rate*.005));setRelease(80);reset();return true;
}
void LookaheadLimiter::setRelease(float ms) noexcept{release_=coefficient(std::clamp(ms,20.0f,500.0f),rate_);}
void LookaheadLimiter::reset() noexcept{frame_=0;head_=tail_=0;gain_=1;audio_.fill({});}
Stereo LookaheadLimiter::tick(Stereo x) noexcept{
    if(!std::isfinite(x.l)||!std::isfinite(x.r))x={};
    const float magnitude=peak(x);const uint64_t oldest=frame_>static_cast<uint64_t>(lookahead_)?frame_-lookahead_:0;
    while(head_!=tail_&&ages_[head_]<oldest)head_=(head_+1)&(kCapacity-1);
    while(head_!=tail_){const int previous=(tail_-1)&(kCapacity-1);if(peaks_[previous]>magnitude)break;tail_=previous;}
    peaks_[tail_]=magnitude;ages_[tail_]=frame_;tail_=(tail_+1)&(kCapacity-1);
    const float target=std::min(1.0f,ceiling/std::max(peaks_[head_],1e-12f));
    gain_=target<gain_?target:std::min(target,1-release_+release_*gain_);
    audio_[frame_&(kCapacity-1)]=x;
    Stereo out=frame_>=static_cast<uint64_t>(lookahead_)?audio_[(frame_-lookahead_)&(kCapacity-1)]*gain_:Stereo{};++frame_;
    // Rounding guard, not the gain-control mechanism; detector bounds every delayed sample.
    out.l=std::clamp(out.l,-ceiling,ceiling);out.r=std::clamp(out.r,-ceiling,ceiling);return out;
}
} // namespace astra::fx
