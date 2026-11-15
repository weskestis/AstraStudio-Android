#include "OfflineRender.h"
#include "core/RenderCore.h"
#include <cstdio>
#include <cstring>
namespace astra::project {
namespace {
void put16(unsigned char* out,uint16_t v){out[0]=v&255;out[1]=v>>8;}
void put32(unsigned char* out,uint32_t v){for(int i=0;i<4;++i)out[i]=(v>>(8*i))&255;}
struct File {std::FILE* f;~File(){if(f)std::fclose(f);}};
}
int renderWave(const char* path,std::span<const double> wire,const Samples& samples,
 const std::array<synth::Patch,16>& patches,const std::array<fx::Config,16>& effects,
 const std::array<float,kMixValues>& mix,int rate,float tail,std::atomic<bool>& cancel,std::atomic<float>& progress){
    if((rate!=44100&&rate!=48000)||!std::isfinite(tail)||tail<0||tail>30||wire.size()<9)return 1;
    for(float x:mix)if(!std::isfinite(x))return 1;
    if(mix[0]<30||mix[0]>300||mix[1]<0||mix[1]>1||mix[2]<0||mix[2]>24||mix[3]<20||mix[3]>500)return 1;
    std::vector<double> arrangement(wire.begin(),wire.end());arrangement[1]=1;arrangement[4]=0;
    auto plan=decodePlan(arrangement,samples);if(!plan||plan->endBeat()<=0)return 1;
    const auto musicFrames=static_cast<uint64_t>(std::ceil(plan->endBeat()*60/mix[0]*rate));
    const auto total=musicFrames+static_cast<uint64_t>(std::ceil(tail*rate));if(total>(0xffffffffULL-36)/6)return 1;
    auto core=std::make_unique<RenderCore>();if(!core->prepare(rate)||!core->setProject(arrangement,samples))return 1;
    if(!core->enqueue({CommandType::Tempo,0,0,0,mix[0]})||!core->enqueue({CommandType::MasterGain,0,0,0,mix[1]})||
       !core->enqueue({CommandType::LimiterDrive,0,0,0,mix[2]})||!core->enqueue({CommandType::LimiterRelease,0,0,0,mix[3]}))return 1;
    for(int t=0;t<16;++t){if(!core->setPatch(t,patches[t])||!core->setEffects(t,effects[t]))return 1;
        constexpr CommandType types[6]={CommandType::TrackGain,CommandType::TrackPan,CommandType::TrackMute,CommandType::TrackSolo,CommandType::SendA,CommandType::SendB};
        for(int n=0;n<6;++n)if(!core->enqueue({types[n],t,0,0,mix[4+t*6+n]}))return 1;
    }
    std::array<float,1024> block{};for(int n=0;n<20;++n)core->render(block.data(),512);
    File file{std::fopen(path,"wb")};if(!file.f)return 2;
    unsigned char header[44]{};std::memcpy(header,"RIFF",4);put32(header+4,static_cast<uint32_t>(total*6+36));std::memcpy(header+8,"WAVEfmt ",8);
    put32(header+16,16);put16(header+20,1);put16(header+22,2);put32(header+24,rate);put32(header+28,rate*6);put16(header+32,6);put16(header+34,24);
    std::memcpy(header+36,"data",4);put32(header+40,static_cast<uint32_t>(total*6));if(std::fwrite(header,1,44,file.f)!=44)return 2;
    core->enqueue({CommandType::Transport,0,0,0,1});const auto latency=static_cast<uint64_t>(core->outputLatencyFrames());
    const auto streamFrames=total+latency;uint64_t done=0;uint32_t rng=0x41535452;std::array<unsigned char,3072> bytes{};
    auto random=[&](){rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;return (rng>>8)*(1.f/16777216);};
    while(done<streamFrames){if(cancel.load(std::memory_order_relaxed))return 3;
        auto count=static_cast<int>(std::min<uint64_t>(512,streamFrames-done));
        if(done<musicFrames&&done+count>musicFrames)count=static_cast<int>(musicFrames-done);
        if(done==musicFrames)core->enqueue({CommandType::Transport,0,0,0,0});
        core->render(block.data(),count);int at=0;
        for(int i=0;i<count;++i)if(done+i>=latency)for(int ch=0;ch<2;++ch){const float dither=random()-random();
            const auto value=static_cast<int32_t>(std::lrint(std::clamp(block[i*2+ch],-1.f,.99999988f)*8388607+dither));
            for(int b=0;b<3;++b)bytes[at++]=static_cast<unsigned char>((static_cast<uint32_t>(value)>>(8*b))&255);}
        if(at&&std::fwrite(bytes.data(),1,static_cast<std::size_t>(at),file.f)!=static_cast<std::size_t>(at))return 2;
        done+=count;progress.store(static_cast<float>(done)/streamFrames,std::memory_order_relaxed);
    }
    return std::fflush(file.f)==0?0:2;
}
}
