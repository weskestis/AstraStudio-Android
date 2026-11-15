#include "WaveWriter.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
namespace astra::project {
namespace {void put(unsigned char* out,uint32_t v,int n){for(int i=0;i<n;++i)out[i]=static_cast<unsigned char>(v>>(i*8));}}
WaveWriter::WaveWriter(const char* path,int rate,uint64_t frames,bool floating)noexcept:float_(floating),remaining_(frames*2){
    const int align=floating?8:6;if(rate<8000||rate>384000||frames>(0xffffffffULL-36)/align)return;
    file_=std::fopen(path,"wb");if(!file_)return;
    unsigned char h[44]{};std::memcpy(h,"RIFF",4);put(h+4,static_cast<uint32_t>(frames*align+36),4);std::memcpy(h+8,"WAVEfmt ",8);
    put(h+16,16,4);put(h+20,floating?3:1,2);put(h+22,2,2);put(h+24,rate,4);put(h+28,rate*align,4);put(h+32,align,2);put(h+34,floating?32:24,2);
    std::memcpy(h+36,"data",4);put(h+40,static_cast<uint32_t>(frames*align),4);ok_=std::fwrite(h,1,44,file_)==44;
}
bool WaveWriter::write(std::span<const float> audio)noexcept {
    if(!ok_||audio.size()>remaining_||audio.size()%2)return false;
    std::array<unsigned char,8192> bytes{};const int size=float_?4:3;
    auto random=[&](){rng_^=rng_<<13;rng_^=rng_>>17;rng_^=rng_<<5;return (rng_>>8)*(1.f/16777216);};
    for(std::size_t start=0;start<audio.size();){const auto count=std::min(audio.size()-start,bytes.size()/size);
        for(std::size_t i=0;i<count;++i){const float v=audio[start+i];if(!std::isfinite(v)){ok_=false;return false;}
            uint32_t encoded;
            if(float_)encoded=std::bit_cast<uint32_t>(v);
            else {const auto quantized=std::lrint(std::clamp(v,-1.f,1.f)*8388608.0+random()-random());encoded=static_cast<uint32_t>(std::clamp<long>(quantized,-8388608,8388607));}
            put(bytes.data()+i*size,encoded,size);}
        if(std::fwrite(bytes.data(),size,count,file_)!=count){ok_=false;return false;}start+=count;}
    remaining_-=audio.size();return true;
}
bool WaveWriter::finish()noexcept {return ok_&&remaining_==0&&std::fflush(file_)==0;}
}
