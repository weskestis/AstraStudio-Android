#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <numbers>
#include <sys/resource.h>
#include <unistd.h>
using namespace astra;
using namespace astra::project;
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
namespace {
int checks{};
void check(bool value,const char* label){++checks;if(!value){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
void tail(std::vector<double>& w,int version){
    if(version>=2)for(int t=0;t<(version<5?16:kTracks);++t){w.push_back(0);for(int z=0;z<17;++z)w.insert(w.end(),{-1,0,0,0,0,60,0,1,0,.005,.2,.8,.08,0,0,0,0});}
    if(version>=3)w.insert(w.end(),{0,0});
    if(version>=4)w.push_back(0);
}
std::vector<double> audioWire(int tracks){
    std::vector<double> w{5,1,4,4,0,0,16,double(tracks),double(tracks)};
    for(int t=0;t<tracks;++t)w.insert(w.end(),{double(t),.5,0,0,48000,0,0});
    for(int t=0;t<tracks;++t)w.insert(w.end(),{double(t+1),double(t),0,.5,0,0});
    tail(w,5);return w;
}
struct Temp{char path[32]="/tmp/astra-capacity-XXXXXX";Temp(){const int fd=mkstemp(path);check(fd>=0,"temporary WAV");close(fd);}~Temp(){std::remove(path);}};
void render(RenderCore& core,std::array<float,1024>& b){inRender=true;core.render(b.data(),512);inRender=false;}
}
int main(){
    static_assert(kTracks==50&&kClips==400&&kMaxRegions==2048&&kVoiceCount==128);
    for(int version=1;version<=4;++version){
        std::vector<double> w{double(version),1,4,4,0,0,16,128,128};
        for(int slot=0;slot<128;++slot)w.insert(w.end(),{double(slot),1,-1,0,0,1,0,0,.25,60,1});
        for(int slot=0;slot<128;++slot)w.insert(w.end(),{double(slot+1),double(slot),double(slot/16),1,0,0});
        tail(w,version);auto p=decodePlan(w,{});check(p!=nullptr,"legacy native project decodes");
        for(int slot=0;slot<128;++slot){const int target=slot/16*kTracks+slot%16;check(p->clips[target].present,"legacy clip scene stride migrates");check(p->regions[slot%16][slot/16].slot==target,"legacy region follows migrated source");}
        for(int t=16;t<kTracks;++t)check(!p->clips[t].present&&p->instruments[t].mode==0,"added tracks start empty");
    }
    {std::vector<double> w{5,1,4,4,0,0,16,400,2048};
        for(int slot=0;slot<400;++slot){w.insert(w.end(),{double(slot),64,-1,0,0,256,256});
            for(int n=0;n<256;++n)w.insert(w.end(),{n*.25,.125,60,.5});
            for(int lane=0;lane<4;++lane)for(int n=0;n<64;++n)w.insert(w.end(),{double(lane),double(n),lane==2?1200:.5});}
        for(int n=0;n<2048;++n)w.insert(w.end(),{double(n+1),double(n%50),double(n/50*64),64,0,0});
        tail(w,5);check(w.size()>400000&&w.size()<=kProjectWireLimit,"full project exceeds old wire budget and fits new budget");
        auto p=decodePlan(w,{});check(p&&p->clips[399].notes.size()==256&&p->regions[49].size()==40,"all 400 dense clips and 2048 regions decode");
        w[9]=400;check(!decodePlan(w,{}),"clip slot beyond 400 rejected");
        check(!decodePlan(std::vector<double>(kProjectWireLimit+1),{}),"oversized native payload rejected");
    }
    {auto core=std::make_unique<RenderCore>();std::array<float,1024> b{};
        for(int t=0;t<kTracks;++t){check(core->setPatch(t,synth::preset(0)),"all 50 patches publish before a callback");check(core->setEffects(t,fx::Config{}),"all 50 FX snapshots publish before a callback");
            check(core->enqueue({CommandType::NoteOn,t,40+t%12,uint32_t(t+1),.02f}),"MIDI reaches every track");}
        render(*core,b);check(core->snapshot().activeVoices==50,"50 simultaneous track voices");check(core->snapshot().droppedCommands==0,"initial 50-track synchronization loses no commands");
        check(!core->setPatch(50,synth::preset(0))&&!core->enqueue({CommandType::TrackGain,50,0,0,1}),"track 51 is rejected");
        core->enqueue({CommandType::MeterTrack,0,0,0,49});render(*core,b);check(core->effectsSnapshot().meterTrack==49,"Track 50 spectrum selected");
        core->enqueue({CommandType::MeterTrack,0,0,0,50});render(*core,b);check(core->effectsSnapshot().meterTrack==50,"master meter follows expanded tracks");
        auto sidechain=fx::Config{};sidechain[fx::ScOn]=1;sidechain[fx::ScSource]=49;sidechain[fx::ScThreshold]=-60;sidechain[fx::ScAttack]=.1;
        check(core->setEffects(0,sidechain),"Track 50 external sidechain accepted");
        for(int n=0;n<50;++n)render(*core,b);
        check(core->effectsSnapshot().sidechain[0]>.1,"Track 50 sidechain detector actually reduces Track 1 gain");
        core->panic();render(*core,b);check(core->snapshot().activeVoices==0,"panic releases all 50 track voices");
    }
    {auto kernel=std::make_unique<sampler::Kernel>();Sequencer seq;check(seq.prepare(48000,kernel.get()),"prepare expanded sequencer");Plan p;auto& c=p.clips[399];c.present=true;c.length=4;
        seq.activate(&p);seq.scene(7,0,1);std::array<fx::Stereo,kTracks> audio{};
        inRender=true;seq.tick(0,120,audio);inRender=false;check(seq.snapshot().active[49]==399,"scene 8 launches Track 50 clip");
        seq.launch(49,-1,0,1);inRender=true;seq.tick(0,120,audio);inRender=false;check(seq.snapshot().active[49]==-1,"Track 50 stops independently");
    }
    {Samples samples{};auto s=std::make_shared<Sample>();s->rate=48000;s->stereo.resize(96000);
        for(int n=0;n<48000;++n){s->stereo[n*2]=.002f*std::sin(2*std::numbers::pi*55*n/48000);s->stereo[n*2+1]=-s->stereo[n*2];}samples[0]=s;
        const auto wire=audioWire(50);std::array<synth::Patch,kTracks> patches{};std::array<fx::Config,kTracks> effects{};std::array<float,kMixValues> mix{};
        mix[0]=120;mix[1]=.8;mix[3]=80;for(int t=0;t<kTracks;++t)mix[4+t*6]=1;
        std::atomic<bool> cancel{false};std::atomic<float> progress{};Temp all,single,raw;
        const auto began=std::chrono::steady_clock::now();
        check(renderWave(all.path,wire,samples,patches,effects,mix,192000,0,cancel,progress,false)==0,"all 50 audio layers render a 192 kHz PCM24 master");
        const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-began).count();
        mix[4+49*6+3]=1;
        check(renderWave(single.path,wire,samples,patches,effects,mix,192000,0,cancel,progress,false)==0,"solo Track 50 master export");
        check(renderWave(raw.path,wire,samples,patches,effects,mix,192000,0,cancel,progress,true,49)==0,"Track 50 resampling tap");
        const auto a=readSample(all.path,kSampleBudget),b=readSample(single.path,kSampleBudget),d=readSample(raw.path,kSampleBudget);
        check(a&&b&&d&&a->rate==192000&&a->frames()==48000&&d->frames()==48000,"all renders have exact rate and musical duration");
        double energy=0,error=0,dryEnergy=0;for(std::size_t i=4000;i<a->stereo.size()-4000;++i){energy+=a->stereo[i]*a->stereo[i];const double difference=a->stereo[i]-b->stereo[i]*50;error+=difference*difference;dryEnergy+=d->stereo[i]*d->stereo[i];}
        check(energy>1&&error/energy<1e-6,"50-track sum equals 50 copies of isolated Track 50 to PCM24 precision");check(dryEnergy>.01,"resampling high track is audible");
        rusage usage{};getrusage(RUSAGE_SELF,&usage);
        std::printf("50-layer host render: 0.25 s at 192 kHz in %.3f s; process peak RSS %.1f MiB (includes prior tests).\n",seconds,usage.ru_maxrss/1024.0);
    }
    check(realtimeAllocations==0&&realtimeDeallocations==0,"expanded callback never allocates or frees");
    std::printf("Capacity tests passed: %d checks; 50 tracks / 400 clips / 2048 regions; realtime allocations=%d frees=%d.\n",checks,realtimeAllocations.load(),realtimeDeallocations.load());
}
