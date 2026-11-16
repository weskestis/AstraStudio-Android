#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <numbers>
#include <unistd.h>
using namespace astra;
using namespace astra::project;
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
namespace {
int checks{};
void check(bool v,const char* label){++checks;if(!v){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
struct Temp {char path[32]="/tmp/astra-freeze-XXXXXX";Temp(){int fd=mkstemp(path);check(fd>=0,"temporary wave");close(fd);}~Temp(){std::remove(path);}};
std::vector<double> wire(int frozen=-1,int64_t music=48000,bool tempo=false){
    std::vector<double> w{6,1,4,4,0,0,16,2,2,
        0,2,-1,0,0,2,4,0,.25,45,.6,1.5,.4,48,.5,
        0,0,.8,1,0,-.2,2,0,1800,3,0,.25,
        1,2,1,0,96000,0,0,
        1,0,0,2,0,0,2,1,0,2,0,0};
    for(int t=0;t<kTracks;++t){w.push_back(0);for(int z=0;z<17;++z)w.insert(w.end(),{-1,0,0,0,0,60,0,1,0,.005,.2,.8,.08,0,0,0,0});}
    if(tempo)w.insert(w.end(),{1,3,0,120,1,1,90,0,1.5,150,0});else w.insert(w.end(),{0,0});
    w.push_back(0);w.push_back(frozen>=0?1:0);if(frozen>=0)w.insert(w.end(),{0,double(frozen),double(music)});return w;
}
auto tone(int rate,int frames){auto s=std::make_shared<Sample>();s->rate=rate;s->stereo.resize(frames*2);
    for(int n=0;n<frames;++n){const float x=.04f*std::sin(2*std::numbers::pi*55*n/rate);s->stereo[n*2]=x;s->stereo[n*2+1]=-x;}return s;}
std::array<float,kMixValues> mix(){std::array<float,kMixValues> m{};m[0]=120;m[1]=.7;m[3]=80;for(int t=0;t<kTracks;++t)m[4+t*6]=.7;return m;}
void block(RenderCore& c,std::array<float,1024>& b,int n=512){inRender=true;c.render(b.data(),n);inRender=false;}
double energy(const Sample& s,int first,int last){double sum=0;for(int i=first*2;i<last*2;++i)sum+=s.stereo[i]*s.stereo[i];return sum;}
struct Observer {int ons{},offs{};static void note(void* p,int,int,float,uint32_t,bool on)noexcept{auto& o=*static_cast<Observer*>(p);if(on)++o.ons;else ++o.offs;}};
}
int main(){
    Samples samples{};samples[1]=tone(48000,96000);auto m=mix();std::array<synth::Patch,kTracks> patches{};std::array<fx::Config,kTracks> effects{};
    patches[0][synth::Env1Release]=.4f;
    effects[0][fx::ShaperOn]=1;effects[0][fx::ShaperPre]=3;effects[0][fx::ScOn]=1;effects[0][fx::ScSource]=1;effects[0][fx::ScThreshold]=-45;
    std::atomic<bool> cancel{false};std::atomic<float> progress{};
    for(int rate:{48000,96000,192000})for(bool tempo:{false,true}){
        Temp source,live,cached;const auto original=wire(-1,0,tempo);const auto p=decodePlan(original,samples);check(p!=nullptr,"new source wire decodes");
        const int music=static_cast<int>(std::ceil(p->tempo.seconds(0,2,120)*rate));
        check(renderWave(source.path,original,samples,patches,effects,m,rate,.5f,cancel,progress,true,0)==0,"whole-track dry float freeze renders");
        auto frozen=readSample(source.path,kSampleBudget);check(frozen&&frozen->rate==rate&&frozen->frames()==music+rate/2,"freeze retains exact musical duration and tail");
        check(energy(*frozen,rate/5,rate/3)>.001,"instrument release continues between notes");check(energy(*frozen,music,music+rate/10)>.001,"instrument release tail is present after arrangement end");
        Samples bank=samples;bank[0]=frozen;const auto cachedWire=wire(0,music,tempo);
        check(renderWave(live.path,original,samples,patches,effects,m,rate,.5f,cancel,progress,true)==0,"live source with FX, sidechain and automation renders");
        check(renderWave(cached.path,cachedWire,bank,patches,effects,m,rate,.5f,cancel,progress,true)==0,"frozen source through live FX and sidechain renders");
        auto a=readSample(live.path,kSampleBudget),b=readSample(cached.path,kSampleBudget);check(a&&b&&a->frames()==b->frames(),"frozen and live master lengths match");
        double error=0,power=0;for(std::size_t i=0;i<a->stereo.size();++i){double diff=a->stereo[i]-b->stereo[i];error+=diff*diff;power+=a->stereo[i]*a->stereo[i];}
        std::printf("Freeze %d Hz, tempo %d: relative squared error %.9g\n",rate,tempo,error/std::max(1e-15,power));
        check(power>.01&&error/power<1e-9,"freeze matches live master, including automation and release tails");
        if(rate==192000&&!tempo){Temp pcm;check(renderWave(pcm.path,cachedWire,bank,patches,effects,m,192000,.5f,cancel,progress,false)==0,"frozen project exports actual 192 kHz PCM24");
            auto s=readSample(pcm.path,kSampleBudget);check(s&&s->frames()==288000&&s->rate==192000,"PCM24 frozen master exact length and sample rate");}
        if(rate==48000&&!tempo){
            auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(cachedWire,bank),"publish freeze plan");core->sourceTrack(0);core->enqueue({CommandType::Tempo,0,0,0,120});core->enqueue({CommandType::Transport,0,0,0,1});
            for(int n=0;n<10;++n){block(*core,out);}check(core->snapshot().activeVoices==0,"Arrangement freeze skips original instrument voices");
            core->enqueue({CommandType::Seek,0,0,0,.5});block(*core,out,1);check(std::abs(out[0]-frozen->stereo[24000])<1e-6,"seek addresses exact cached frame");
            core->enqueue({CommandType::Transport,0,0,0,0});block(*core,out);double stopped=0;for(float x:out)stopped+=std::abs(x);check(stopped==0,"mid-song stop does not play future frozen phrases");
            core->enqueue({CommandType::Seek,0,0,0,.05});core->enqueue({CommandType::Transport,0,0,0,1});block(*core,out);
            check(core->setProject(original,bank),"unfreeze while transport runs");block(*core,out);check(core->snapshot().activeVoices>0,"unfreeze re-enters held source notes immediately");
            auto session=cachedWire;session[1]=0;check(core->setProject(session,bank),"frozen metadata preserved in Session plan");core->enqueue({CommandType::LaunchScene,0,0,0,0});block(*core,out);check(core->snapshot().activeVoices>0,"Session uses original instrument even with an Arrangement freeze");
            auto bad=cachedWire;bad.back()=frozen->frames()+1;check(!decodePlan(bad,bank),"invalid cached music frame count rejected");bad=cachedWire;bad[bad.size()-3]=50;check(!decodePlan(bad,bank),"invalid frozen track rejected");bad=cachedWire;bad[bad.size()-2]=63;check(!decodePlan(bad,bank),"missing freeze asset rejected");bad=cachedWire;bad[bad.size()-4]=2;bad.insert(bad.end(),{1,0,double(music)});check(!decodePlan(bad,bank),"duplicate cached asset rejected");
        }
    }
    { // Freeze already-warped audio, then retain its original editable warp state.
        auto original=wire(-1,0,true);
        original.erase(original.begin()+9,original.begin()+36);
        original.insert(original.begin()+9,{0,2,1,0,96000,0,0});
        original.erase(original.end()-2,original.end());original.insert(original.end(),{1,0,1,138,0,-7,0});
        const auto plan=decodePlan(original,samples);check(plan!=nullptr,"warped audio freeze fixture decodes");
        const int music=static_cast<int>(std::ceil(plan->tempo.seconds(0,2,120)*48000));Temp source,live,cached;
        check(renderWave(source.path,original,samples,patches,effects,m,48000,2.f,cancel,progress,true,0)==0,"warped source renders to freeze");
        Samples bank=samples;bank[0]=readSample(source.path,kPlaybackBudget,true);check(bank[0]&&bank[0]->stream,"freeze uses real bounded disk pages");
        auto frozenWire=original;frozenWire.back()=1;frozenWire.insert(frozenWire.end(),{0,0,double(music)});
        check(renderWave(live.path,original,samples,patches,effects,m,48000,2.f,cancel,progress,true)==0,"original warped audio master");
        check(renderWave(cached.path,frozenWire,bank,patches,effects,m,48000,2.f,cancel,progress,true)==0,"streamed freeze export uses complete offline reads");
        auto a=readSample(live.path,kSampleBudget),b=readSample(cached.path,kSampleBudget);check(a&&b&&a->stereo==b->stereo,"warped source and streamed freeze produce identical master samples");
    }
    {auto kernel=std::make_unique<sampler::Kernel>();Sequencer seq;Observer observer;seq.bind(&observer,Observer::note,nullptr);check(seq.prepare(48000,kernel.get()),"prepare cached sequencer");
        Plan p;p.mode=1;p.samples[0]=tone(48000,96000);p.frozen[0]={0,48000};p.tempo={true,{{0,120,true},{1,60,false}}};auto& c=p.clips[0];c.present=true;c.length=2;c.notes={{0,2,60,1}};p.regions[0]={{1,0,0,2,0,0}};seq.activate(&p);
        std::array<fx::Stereo,kTracks> out{};inRender=true;seq.tick(.5,120,out);seq.renderFrozen(.5,120,true,out);inRender=false;
        const double frame=p.tempo.seconds(0,.5,120)*48000;SampleCursor cursor;const auto expected=kernel->read(*p.samples[0],cursor,frame,0,96000,0,true);
        check(std::abs(out[0].l-expected.l)<1e-6&&observer.ons==0,"tempo ramp seeks frozen audio without triggering original notes");
        inRender=true;seq.tick(0,120,out);seq.renderFrozen(0,120,true,out);inRender=false;check(std::abs(out[0].l)<1e-7,"loop wrap restarts at the correct frozen sample");
        auto live=p;live.frozen={};seq.activate(&live);inRender=true;seq.tick(.1,120,out);inRender=false;check(observer.ons==1,"sequencer re-chases sustained notes when unfreezing");
    }
    {auto live=std::make_unique<RenderCore>(),frozen=std::make_unique<RenderCore>();auto p=synth::preset(3);check(live->setPatch(0,p),"heavy patch");
        std::array<float,1024> b{};for(int n=0;n<12;++n)live->enqueue({CommandType::NoteOn,0,36+n,uint32_t(n+1),.03f});block(*live,b);
        auto s=tone(48000,96000);Samples bank=samples;bank[0]=s;check(frozen->setProject(wire(0,48000),bank),"performance frozen fixture");frozen->enqueue({CommandType::Transport,0,0,0,1});block(*frozen,b);
        const auto run=[&](RenderCore& core){const auto start=std::chrono::steady_clock::now();for(int n=0;n<32;++n)block(core,b);return std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();};
        const double liveSeconds=run(*live),frozenSeconds=run(*frozen);check(live->snapshot().activeVoices>=12&&frozen->snapshot().activeVoices==0,"freeze removes measured instrument work");
        std::printf("Host-only 16384-frame benchmark: 12 instrument voices %.4f s; cached audio %.4f s. No phone performance guarantee.\n",liveSeconds,frozenSeconds);
    }
    check(realtimeAllocations==0&&realtimeDeallocations==0,"frozen playback, seek, stop, unfreeze and Session callbacks allocate/free nothing");
    std::printf("Freeze tests passed: %d checks; realtime allocations=%d frees=%d.\n",checks,realtimeAllocations.load(),realtimeDeallocations.load());
}
