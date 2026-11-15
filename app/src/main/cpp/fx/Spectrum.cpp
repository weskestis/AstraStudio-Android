#include "Spectrum.h"
namespace astra::fx {
Spectrum::Spectrum() noexcept{
    display_.fill(-100);
    for(int i=0;i<kSize;++i){window_[i]=.5f-.5f*std::cos(2*std::numbers::pi_v<float>*i/(kSize-1));windowSum_+=window_[i];}
}
void Spectrum::push(Stereo sample,int track) noexcept{if(!queue_.push({sample,track}))dropped_.fetch_add(1,std::memory_order_relaxed);}
void Spectrum::fft() noexcept{
    for(int i=1,j=0;i<kSize;++i){int bit=kSize>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)std::swap(work_[i],work_[j]);}
    for(int length=2;length<=kSize;length*=2){const float angle=-2*std::numbers::pi_v<float>/length;const std::complex<float> step{std::cos(angle),std::sin(angle)};
        for(int start=0;start<kSize;start+=length){std::complex<float> twiddle{1,0};for(int j=0;j<length/2;++j){const auto a=work_[start+j],b=work_[start+j+length/2]*twiddle;work_[start+j]=a+b;work_[start+j+length/2]=a-b;twiddle*=step;}}}
}
std::array<float,Spectrum::kBands> Spectrum::analyze(float sampleRate) noexcept{
    Sample next{};bool fresh=false;
    for(int i=0;i<32768&&queue_.pop(next);++i){fresh=true;if(next.track!=track_){track_=next.track;filled_=at_=0;display_.fill(-100);}
        history_[at_]=next.audio;at_=(at_+1)&(kSize-1);filled_=std::min(kSize,filled_+1);}
    if(!fresh||filled_<kSize)return display_;
    power_.fill(0);
    for(int channel=0;channel<2;++channel){for(int i=0;i<kSize;++i){const auto frame=history_[(at_+i)&(kSize-1)];work_[i]={(channel==0?frame.l:frame.r)*window_[i],0};}
        fft();for(int i=0;i<=kSize/2;++i)power_[i]+=std::norm(work_[i])*.5f;}
    const float maximum=std::min(20000.0f,sampleRate*.475f);
    for(int band=0;band<kBands;++band){const float low=20*std::pow(maximum/20,band/static_cast<float>(kBands));const float high=20*std::pow(maximum/20,(band+1.0f)/kBands);
        const int first=std::clamp(static_cast<int>(std::floor(low*kSize/sampleRate)),1,kSize/2);
        const int last=std::clamp(static_cast<int>(std::ceil(high*kSize/sampleRate)),first,kSize/2);float p=0;
        for(int i=first;i<=last;++i)p=std::max(p,power_[i]);
        const float db=std::clamp(gainDb(2*std::sqrt(p)/windowSum_),-100.0f,12.0f);display_[band]=std::max(db,display_[band]-3.0f);}
    return display_;
}
} // namespace astra::fx
