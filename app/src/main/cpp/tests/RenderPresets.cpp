#include "core/RenderCore.h"
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace {
void u16(std::FILE* f,uint16_t v){std::fputc(v&255,f);std::fputc((v>>8)&255,f);}
void u32(std::FILE* f,uint32_t v){u16(f,static_cast<uint16_t>(v));u16(f,static_cast<uint16_t>(v>>16));}
}
int main(int argc,char** argv) {
    if(argc!=2){std::fprintf(stderr,"Usage: astra_render_presets OUTPUT.wav\n");return 1;}
    constexpr int rate=48000, segment=rate*4;
    std::vector<int16_t> pcm;pcm.reserve(segment*2*astra::synth::kPresetCount);
    double worst=0;
    for(int patch=0;patch<astra::synth::kPresetCount;++patch){
        auto core=std::make_unique<astra::RenderCore>();core->setPatch(0,astra::synth::preset(patch));
        for(int i=0;i<8;++i){const int key=patch==3||patch==7?65+(i%3)*3:29+(i%3)*7;const uint64_t at=static_cast<uint64_t>(i*rate*.4);
            core->enqueue({astra::CommandType::NoteOn,0,key,static_cast<uint32_t>(i+1),.9f,at});
            core->enqueue({astra::CommandType::NoteOff,0,0,static_cast<uint32_t>(i+1),0,at+rate/4});}
        std::array<float,512> block{};double squares=0;float peak=0;
        const auto begin=std::chrono::steady_clock::now();
        for(int i=0;i<segment;i+=256){const int n=std::min(256,segment-i);core->render(block.data(),n);
            for(int j=0;j<n*2;++j){const float x=block[j];peak=std::max(peak,std::abs(x));squares+=x*x;pcm.push_back(static_cast<int16_t>(std::lround(x*32767)));}}
        const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count();worst=std::max(worst,seconds/4);
        std::printf("%d %s: peak %.5f, RMS %.5f, host rendering %.2f%% realtime\n",patch,astra::synth::presetNames[patch],peak,std::sqrt(squares/(segment*2)),seconds/4*100);
    }
    auto* file=std::fopen(argv[1],"wb");if(!file)return 2;
    const auto bytes=static_cast<uint32_t>(pcm.size()*2);
    std::fwrite("RIFF",1,4,file);u32(file,36+bytes);std::fwrite("WAVEfmt ",1,8,file);u32(file,16);u16(file,1);u16(file,2);u32(file,rate);u32(file,rate*4);u16(file,4);u16(file,16);std::fwrite("data",1,4,file);u32(file,bytes);
    for(auto v:pcm)u16(file,static_cast<uint16_t>(v));
    const int result=std::fclose(file);std::printf("32-second deterministic audition; worst host render ratio %.4f. This is not an Xperia benchmark.\n",worst);return result==0?0:3;
}
