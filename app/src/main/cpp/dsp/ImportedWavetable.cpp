#include "ImportedWavetable.h"
#include "DspMath.h"
#include <algorithm>
#include <cmath>
#include <complex>
#include <numbers>
#include <stdexcept>
namespace astra::synth {
namespace {
void fft(std::vector<std::complex<double>>& a,bool inverse){
    const auto n=a.size();for(std::size_t i=1,j=0;i<n;++i){auto bit=n>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)std::swap(a[i],a[j]);}
    for(std::size_t len=2;len<=n;len<<=1){const auto root=std::polar(1.0,(inverse?2.0:-2.0)*std::numbers::pi/len);
        for(std::size_t i=0;i<n;i+=len){std::complex<double> w=1;for(std::size_t j=0;j<len/2;++j){auto u=a[i+j],v=a[i+j+len/2]*w;a[i+j]=u+v;a[i+j+len/2]=u-v;w*=root;}}}
    if(inverse)for(auto& x:a)x/=static_cast<double>(n);
}
}
std::size_t ImportedWavetable::requiredBytes(int cycle,int frames)noexcept{std::size_t stride=0,levels=0;for(int n=cycle;n>=4;n>>=1){stride+=std::max(n,std::min(512,cycle))+1;++levels;}return stride*frames*sizeof(float)+levels*sizeof(std::size_t);}
ImportedWavetable::ImportedWavetable(std::span<const float> stereo,int cycle):cycle_(cycle){
    if(cycle<32||cycle>2048||(cycle&(cycle-1))||stereo.empty()||stereo.size()%(cycle*2)||stereo.size()/cycle/2>256)throw std::invalid_argument("Invalid wavetable dimensions");
    frames_=int(stereo.size()/cycle/2);
    for(int n=cycle;n>=4;n>>=1){offsets_.push_back(stride_);stride_+=std::max(n,std::min(512,cycle))+1;++levels_;}
    data_.resize(stride_*frames_);
    std::vector<std::complex<double>> spectrum(cycle);
    for(int f=0;f<frames_;++f){for(int i=0;i<cycle;++i){const float v=stereo[(f*cycle+i)*2];if(!std::isfinite(v))throw std::invalid_argument("Nonfinite wavetable");spectrum[i]=v;data_[f*stride_+i]=v;}
        data_[f*stride_+cycle]=data_[f*stride_];fft(spectrum,false);
        for(int l=1;l<levels_;++l){const int n=std::max(cycle>>l,std::min(512,cycle));const int harmonics=(cycle>>l)/2;std::vector<std::complex<double>> bins(n);
            const double scale=double(n)/cycle;bins[0]=spectrum[0]*scale;
            for(int h=1;h<harmonics;++h){bins[h]=spectrum[h]*scale;bins[n-h]=spectrum[cycle-h]*scale;}
            fft(bins,true);const auto offset=f*stride_+offsets_[l];for(int i=0;i<n;++i)data_[offset+i]=static_cast<float>(bins[i].real());data_[offset+n]=data_[offset];
        }
    }
}
float ImportedWavetable::mipFor(float hz,float sr)const noexcept{return std::clamp(std::log2(std::max(hz,.001f)*(cycle_*.5f)/(.42f*sr))+1.f,0.f,float(levels_-1));}
float ImportedWavetable::lookup(int frame,int level,double phase)const noexcept{const int n=std::max(cycle_>>level,std::min(512,cycle_));const double p=wrap(phase)*n;const int i=int(p);const auto at=frame*stride_+offsets_[level]+i;return lerp(data_[at],data_[at+1],float(p-i));}
float ImportedWavetable::read(float position,double phase,float mip)const noexcept{
    const float p=unit(position)*(frames_-1);const int a=int(p),b=std::min(a+1,frames_-1);
    mip=std::clamp(mip,0.f,float(levels_-1));const int l=int(mip),m=std::min(l+1,levels_-1);
    return lerp(lerp(lookup(a,l,phase),lookup(a,m,phase),mip-l),lerp(lookup(b,l,phase),lookup(b,m,phase),mip-l),p-a);
}
}
