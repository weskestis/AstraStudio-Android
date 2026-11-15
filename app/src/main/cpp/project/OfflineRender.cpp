#include "OfflineRender.h"
#include "WaveWriter.h"
#include "core/RenderCore.h"
#include <cstdio>
#include <cstring>
namespace astra::project {
int renderWave(const char* path,std::span<const double> wire,const Samples& samples,
 const std::array<synth::Patch,16>& patches,const std::array<fx::Config,16>& effects,
 const std::array<float,kMixValues>& mix,int rate,float tail,std::atomic<bool>& cancel,std::atomic<float>& progress,bool floating){
    if((rate!=44100&&rate!=48000)||!std::isfinite(tail)||tail<0||tail>30||wire.size()<9)return 1;
    for(float x:mix)if(!std::isfinite(x))return 1;
    if(mix[0]<30||mix[0]>300||mix[1]<0||mix[1]>1||mix[2]<0||mix[2]>24||mix[3]<20||mix[3]>500)return 1;
    std::vector<double> arrangement(wire.begin(),wire.end());arrangement[1]=1;arrangement[4]=0;
    auto plan=decodePlan(arrangement,samples);if(!plan||plan->endBeat()<=0)return 1;
    const auto musicFrames=static_cast<uint64_t>(std::ceil(plan->tempo.seconds(0,plan->endBeat(),mix[0])*rate));
    const auto total=musicFrames+static_cast<uint64_t>(std::ceil(tail*rate));if(total>(0xffffffffULL-36)/(floating?8:6))return 1;
    auto core=std::make_unique<RenderCore>();if(!core->prepare(rate)||!core->setProject(arrangement,samples))return 1;
    if(!core->enqueue({CommandType::Tempo,0,0,0,mix[0]})||!core->enqueue({CommandType::MasterGain,0,0,0,mix[1]})||
       !core->enqueue({CommandType::LimiterDrive,0,0,0,mix[2]})||!core->enqueue({CommandType::LimiterRelease,0,0,0,mix[3]}))return 1;
    for(int t=0;t<16;++t){if(!core->setPatch(t,patches[t])||!core->setEffects(t,effects[t]))return 1;
        constexpr CommandType types[6]={CommandType::TrackGain,CommandType::TrackPan,CommandType::TrackMute,CommandType::TrackSolo,CommandType::SendA,CommandType::SendB};
        for(int n=0;n<6;++n)if(!core->enqueue({types[n],t,0,0,mix[4+t*6+n]}))return 1;
    }
    std::array<float,1024> block{};for(int n=0;n<20;++n)core->render(block.data(),512);
    WaveWriter writer(path,rate,total,floating);if(!writer.good())return 2;
    core->enqueue({CommandType::Transport,0,0,0,1});const auto latency=static_cast<uint64_t>(core->outputLatencyFrames());
    const auto streamFrames=total+latency;uint64_t done=0;
    while(done<streamFrames){if(cancel.load(std::memory_order_relaxed))return 3;
        auto count=static_cast<int>(std::min<uint64_t>(512,streamFrames-done));
        if(done<musicFrames&&done+count>musicFrames)count=static_cast<int>(musicFrames-done);
        if(done==musicFrames)core->enqueue({CommandType::Transport,0,0,0,0});
        core->render(block.data(),count);const auto skip=done<latency?std::min<uint64_t>(count,latency-done):0;
        if(skip<static_cast<uint64_t>(count)&&!writer.write({block.data()+skip*2,static_cast<std::size_t>((count-skip)*2)}))return 2;
        done+=count;progress.store(static_cast<float>(done)/streamFrames,std::memory_order_relaxed);
    }
    return writer.finish()?0:2;
}
}
