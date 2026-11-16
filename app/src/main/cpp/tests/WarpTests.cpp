#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include <cstdio>
#include <cstdlib>
#include <numbers>
#include <chrono>
#include <unistd.h>
using namespace astra;
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
namespace {
int assertions{};
void check(bool value,const char* label){++assertions;if(!value){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
std::shared_ptr<project::Sample> tone(int rate=48000,double hz=220){auto s=std::make_shared<project::Sample>();s->rate=rate;s->stereo.resize(rate*8);
    for(int64_t i=0;i<s->frames();++i){const float x=static_cast<float>(.4*std::sin(2*std::numbers::pi*hz*i/rate));s->stereo[i*2]=x;s->stereo[i*2+1]=-x;}return s;}
project::Plan plan(const std::shared_ptr<project::Sample>& s){project::Plan p;p.mode=1;p.samples[0]=s;auto& c=p.clips[0];c.present=true;c.asset=0;c.last=s->frames();c.length=8;c.warp=true;c.sourceBpm=120;p.regions[0]={{1,0,0,8,0,0}};return p;}
std::vector<float> render(project::Plan& p,double bpm,double seconds,double seek=0,bool session=false){
    auto kernel=std::make_unique<sampler::Kernel>();project::Sequencer sequencer;check(sequencer.prepare(48000,kernel.get()),"prepare bounded warp buffers");
    sequencer.activate(&p);if(session)sequencer.launch(0,0,seek,0);
    std::vector<float> result(static_cast<std::size_t>(seconds*48000)*2);project::TempoClock clock;double beat=seek;
    const auto start=std::chrono::steady_clock::now();
    inRender=true;for(std::size_t i=0;i<result.size()/2;++i){sequencer.loop(beat);std::array<fx::Stereo,astra::kTrackCount> audio{};sequencer.tick(beat,static_cast<float>(bpm),audio);result[i*2]=audio[0].l;result[i*2+1]=audio[0].r;beat=clock.tick(p.mode==1?&p.tempo:nullptr,beat,bpm,48000);}inRender=false;
    const double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
    std::printf("Warp fixture %.2f s in %.3f s (host, not Xperia).\n",seconds,elapsed);return result;
}
double frequency(const std::vector<float>& samples,double first,double last){int crossings=0;int64_t begin=-1,end=0;
    for(int64_t i=static_cast<int64_t>(first*48000)+1;i<static_cast<int64_t>(last*48000);++i)if(samples[i*2]>=0&&samples[(i-1)*2]<0){if(begin<0)begin=i;end=i;++crossings;}
    return begin>=0&&end>begin?(crossings-1)*48000.0/(end-begin):0;
}
double peakAt(const std::vector<float>& s,double expected){double best=-1;int at=0;
    const int first=std::max(0,int((expected-.12)*48000)),last=std::min(int(s.size()/2)-240,int((expected+.12)*48000));
    for(int i=first;i<last;i+=24){double energy=0;for(int k=0;k<240;++k)energy+=s[(i+k)*2]*s[(i+k)*2];if(energy>best){best=energy;at=i;}}
    check(best>.005,"transient survives warp");return at/48000.0;
}
std::vector<double> wire(const project::Plan& p){const auto& c=p.clips[0];std::vector<double> w{4,1,4,4,0,0,8,1,1,0,c.length,0,double(c.first),double(c.last),0,0,1,0,0,8,0,0};
    for(int t=0;t<16;++t){w.push_back(0);for(int z=0;z<17;++z)w.insert(w.end(),{-1,0,0,0,0,60,0,1,0,.002,.3,1,.05,0,0,0,0});}
    w.push_back(p.tempo.enabled);w.push_back(p.tempo.points.size());for(auto point:p.tempo.points)w.insert(w.end(),{point.beat,point.bpm,double(point.ramp)});
    w.insert(w.end(),{1,0,1,c.sourceBpm,double(c.warpStart),c.pitch});return w;
}
}
int main(){
    auto source=tone();auto p=plan(source);const auto original=source->stereo;
    {const auto x=render(p,120,1);for(std::size_t i=0;i<x.size();++i)check(x[i]==source->stereo[i],"identity warp is sample-exact");}
    for(double bpm:{90.,150.}){auto x=render(p,bpm,2.5);check(std::abs(frequency(x,.3,2.2)-220)<.5,"tempo changes preserve pitch");
        double energy=0;for(std::size_t i=0;i<x.size();i+=2){check(std::isfinite(x[i])&&std::abs(x[i])<1,"finite bounded tonal output");check(std::abs(x[i]+x[i+1])<.002,"stereo antiphase retained");energy+=x[i]*x[i];}check(energy/x.size()>.01,"no accidental silence");}
    for(double shift:{-12.,12.}){p.clips[0].pitch=shift;auto x=render(p,150,2);check(std::abs(frequency(x,.3,1.8)-220*std::exp2(shift/12))<1,"independent pitch transpose");}p.clips[0].pitch=0;
    {auto x=render(p,90,1,3);check(std::abs(frequency(x,.3,.9)-220)<1,"seek reconstructs pitched content");}
    {auto different=plan(tone(44100));auto x=render(different,150,2);check(std::abs(frequency(x,.3,1.8)-220)<1,"source sample-rate conversion precedes warp");}
    {auto sub=plan(tone(48000,55));auto x=render(sub,150,2);check(std::abs(frequency(x,.4,1.8)-55)<.3,"sub-bass pitch stays stable");}
    {auto split=p;split.regions[0]={{1,0,0,3.25,0,0},{2,0,3.25,4.75,3.25,0}};auto whole=render(p,90,4);auto cut=render(split,90,4);check(whole==cut,"adjacent musical split keeps processor continuity exactly");}
    {p.mode=0;auto x=render(p,150,2,0,true);check(std::abs(frequency(x,.3,1.8)-220)<1,"Session launch follows base tempo");p.mode=1;}
    auto drums=std::make_shared<project::Sample>();drums->rate=48000;drums->stereo.resize(384000);
    for(int beat=1;beat<8;beat+=2)for(int n=0;n<4800;++n){const double t=n/48000.;const float x=static_cast<float>(.8*std::sin(2*std::numbers::pi*180*t)*std::exp(-t*65));const auto i=beat*24000+n;drums->stereo[i*2]=drums->stereo[i*2+1]=x;}
    for(bool ramp:{false,true}){auto d=plan(drums);d.tempo={true,{{0,120,ramp},{4,90,false}}};const auto x=render(d,145,d.tempo.seconds(0,8,145));
        for(int b=1;b<8;b+=2){const double expected=d.tempo.seconds(0,b,145);const double measured=peakAt(x,expected);std::printf("Landmark beat %d expected %.5f measured %.5f\n",b,expected,measured);check(std::abs(measured-expected)<.04,"transient timing within 40 ms through steps/ramps");}}
    {auto d=plan(drums);d.loop=true;d.loopStart=0;d.loopEnd=4;auto x=render(d,90,6);check(std::abs(peakAt(x,2./3)-peakAt(x,10./3)+8./3)<.02,"loop preserves musical transient spacing");}
    {auto d=plan(drums);d.clips[0].warpStart=24000;auto x=render(d,90,1);check(peakAt(x,0)<.04,"downbeat removes pre-roll without changing source");}
    {auto mixed=tone();uint32_t rng=1;for(std::size_t i=0;i<mixed->stereo.size();i+=2){rng=rng*1664525U+1013904223U;const float noise=(int32_t(rng)/2147483648.f)*.05f;mixed->stereo[i]+=noise;mixed->stereo[i+1]=mixed->stereo[i]*.3f+noise;}
        auto d=plan(mixed);for(int t=1;t<16;++t){d.clips[t]=d.clips[0];d.regions[t]={{t+1,t,0,8,0,0}};}
        auto x=render(d,150,1);for(float v:x)check(std::isfinite(v),"16 concurrent mixed-material warp processors stay finite");
        d.clips[0].sourceBpm=30;x=render(d,300,.3);for(float v:x)check(std::isfinite(v),"maximum speed remains bounded");
        d.clips[0].sourceBpm=300;x=render(d,30,.3);for(float v:x)check(std::isfinite(v),"minimum speed remains bounded");
    }
    {p.tempo={true,{{0,120,true},{4,90,false}}};const auto w=wire(p);check(project::decodePlan(w,p.samples)!=nullptr,"version 4 native warp decode");auto bad=w;bad[bad.size()-2]=source->frames();check(!project::decodePlan(bad,p.samples),"reject downbeat after source");bad=w;bad.back()=25;check(!project::decodePlan(bad,p.samples),"reject unsupported pitch");
        char path[]="/tmp/astra-warp-XXXXXX";const int fd=mkstemp(path);check(fd>=0,"export fixture");close(fd);
        std::array<synth::Patch,astra::kTrackCount> patches{};std::array<fx::Config,astra::kTrackCount> effects{};std::array<float,project::kMixValues> mix{};mix[0]=145;mix[1]=.7;mix[3]=80;for(int t=0;t<16;++t)mix[4+t*6]=.8;
        std::atomic<bool> cancel{false};std::atomic<float> progress{};
        check(project::renderWave(path,w,p.samples,patches,effects,mix,48000,0,cancel,progress,true)==0,"warped arrangement exports");auto wav=project::readSample(path,project::kSampleBudget);check(wav&&wav->frames()==std::ceil(p.tempo.seconds(0,8,145)*48000),"export exact tempo-map duration");check(std::abs(frequency(wav->stereo,.3,2)-220)<1,"export preserves source pitch");std::remove(path);
    }
    check(source->stereo==original,"source remains unchanged");
    std::printf("RT allocations %d, deallocations %d\n",realtimeAllocations.load(),realtimeDeallocations.load());
    check(realtimeAllocations==0&&realtimeDeallocations==0,"warp launch, seek, loops and processing allocate/free nothing in callback");
    std::printf("Warp tests passed: %d assertions.\n",assertions);
}
