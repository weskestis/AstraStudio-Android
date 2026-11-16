#include "sampler/Sampler.h"
#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <numbers>
#include <unistd.h>
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
using namespace astra;
namespace {
int assertions{};
void check(bool v,const char* label){++assertions;if(!v){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
auto tone(int rate=48000){auto s=std::make_shared<project::Sample>();s->rate=rate;s->stereo.resize(rate*2);for(int i=0;i<rate;++i){s->stereo[i*2]=.5f*std::sin(2*std::numbers::pi*440*i/rate);s->stereo[i*2+1]=-s->stereo[i*2];}return s;}
sampler::Zone zone(){sampler::Zone z;z.asset=0;z.last=z.loopLast=48000;z.attack=0;z.sustain=1;return z;}
void append(std::vector<double>& w,const sampler::Zone& z){w.insert(w.end(),{double(z.asset),double(z.first),double(z.last),double(z.loopFirst),double(z.loopLast),double(z.root),z.pitch,z.gain,z.pan,z.attack,z.decay,z.sustain,z.release,double(z.reverse),double(z.loop),double(z.oneShot),double(z.choke)});}
std::vector<double> wire(int mode,const sampler::Zone& z,bool arranged=false){std::vector<double>w{2,1,4,4,0,0,16,0,0};if(arranged){w[7]=w[8]=1;w.insert(w.end(),{0,1,-1,0,0,1,0,0,.5,60,1,1,0,0,1,0,0});}
    for(int t=0;t<16;++t){w.push_back(t==0?mode:0);append(w,t==0?z:sampler::Zone{});for(int p=0;p<16;++p)append(w,t==0?z:sampler::Zone{});}return w;}
void render(RenderCore& c,std::array<float,1024>& out){inRender=true;c.render(out.data(),512);inRender=false;}
}
int main(){
    sampler::Kernel kernel;auto sample=tone();auto z=zone();sampler::Voice v;
    for(int note:{48,60,72}){v.start(sample.get(),z,note,1,48000,false);int crossings=0;float last=0;for(int i=0;i<12000;++i){auto out=v.tick(kernel);if(i>100&&last<=0&&out.l>0)++crossings;check(std::abs(out.l+out.r)<1e-6,"stereo phase preserved");last=out.l;}check(std::abs(crossings-110*std::exp2((note-60)/12.0))<=3,"chromatic transposition frequency");}
    {v.start(sample.get(),z,60,1,48000,false);for(int i=0;i<100;++i)v.tick(kernel);v.release();for(int i=0;i<2500;++i)v.tick(kernel);check(!v.active(),"gate release ends sampler voice");z.oneShot=true;v.start(sample.get(),z,60,1,48000,true);for(int i=0;i<100;++i)v.tick(kernel);v.release();for(int i=0;i<2500;++i)v.tick(kernel);check(v.active(),"one-shot ignores MIDI note-off");v.choke();for(int i=0;i<250;++i)v.tick(kernel);check(!v.active(),"choke fades a one-shot in 5 ms");z.oneShot=false;}
    {z.loop=true;z.loopFirst=1000;z.loopLast=2000;for(bool reverse:{false,true}){z.reverse=reverse;v.start(sample.get(),z,72,.5f,48000,false);for(int i=0;i<96000;++i){auto out=v.tick(kernel);check(std::isfinite(out.l)&&std::abs(out.l)<=.5,"bounded forward/reverse looping");}check(v.active(),"loop stays active while held");v.release();for(int i=0;i<3000;++i)v.tick(kernel);check(!v.active(),"loop exits on release");}z=zone();}
    {auto ramp=std::make_shared<project::Sample>();ramp->rate=48000;ramp->stereo.resize(2000);for(int i=0;i<1000;++i)ramp->stereo[i*2]=ramp->stereo[i*2+1]=i/1000.f;z.last=z.loopLast=1000;z.reverse=true;v.start(ramp.get(),z,60,1,48000,false);float at100=0,at200=0;for(int i=0;i<=200;++i){auto o=v.tick(kernel);if(i==100)at100=o.l;if(i==200)at200=o.l;}check(at100>at200,"reverse starts from trimmed end");z=zone();}
    project::Samples samples{};samples[0]=sample;auto data=wire(1,z);
    check(project::decodePlan(data,samples)!=nullptr,"version 2 sample plan decodes");
    for(int i=9;i<299;++i){auto bad=data;bad[i]=std::numeric_limits<double>::quiet_NaN();check(!project::decodePlan(bad,samples),"reject nonfinite sample fields");}
    {auto bad=data;bad[12]=48001;check(!project::decodePlan(bad,samples),"reject source overrun");bad=data;bad[24]=bad[25]=1;check(!project::decodePlan(bad,samples),"reject looped one shot");bad=data;bad.pop_back();check(!project::decodePlan(bad,samples),"reject incomplete instrument block");}
    {auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(data,samples),"publish sampler");core->enqueue({CommandType::NoteOn,0,60,1,1});render(*core,out);check(core->snapshot().activeVoices==1,"sample uses shared voice pool");check(core->snapshot().peakLeft>0,"sample reaches master mixer");
        auto next=wire(1,z);check(core->setProject(next,samples),"republish unchanged sample");render(*core,out);check(core->snapshot().activeVoices==1,"unrelated project edit preserves sample voice");
        project::Samples replacement{};replacement[0]=tone(24000);auto shortZone=z;shortZone.last=shortZone.loopLast=24000;check(core->setProject(wire(1,shortZone),replacement),"replace sample ownership");render(*core,out);check(core->snapshot().activeVoices==0,"retire old sample voice before reclaim");
        core->enqueue({CommandType::Preview,0,0,0,.5});render(*core,out);core->enqueue({CommandType::Preview,0,0,0,.5});core->panic();render(*core,out);check(core->snapshot().peakLeft==0,"panic clears browser audition");}
    {auto drum=zone();drum.oneShot=true;auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(wire(2,drum),samples),"publish polyphonic pad");core->enqueue({CommandType::NoteOn,0,36,21,1});render(*core,out);core->enqueue({CommandType::NoteOn,0,36,22,1});render(*core,out);check(core->snapshot().activeVoices==2,"unchoked pad retriggers overlap with distinct note identities");}
    {auto drums=zone();drums.choke=1;drums.oneShot=true;auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(wire(2,drums),samples),"publish drum rack");core->enqueue({CommandType::NoteOn,0,36,11,1});render(*core,out);core->enqueue({CommandType::NoteOn,0,46,12,1});render(*core,out);check(core->snapshot().activeVoices==1,"same-track drum choke leaves newest pad");core->enqueue({CommandType::Transport,0,0,0,0});for(int i=0;i<8;++i)render(*core,out);check(core->snapshot().activeVoices==0,"transport stop releases one shots");}
    {std::array<synth::Patch,astra::kTrackCount> patches;std::array<fx::Config,astra::kTrackCount> effects;std::array<float,project::kMixValues> mix{};mix[0]=120;mix[1]=.7;mix[3]=80;for(int t=0;t<16;++t)mix[4+t*6]=.8;
        char path[]="/tmp/astra-sampler-XXXXXX";const int fd=mkstemp(path);check(fd>=0,"temp output");close(fd);std::atomic<bool> cancel{};std::atomic<float> progress{};
        check(project::renderWave(path,wire(1,z,true),samples,patches,effects,mix,48000,.1f,cancel,progress)==0,"sampled MIDI reaches WAV exporter");auto output=project::readSample(path,project::kSampleBudget);check(output&&output->frames()==28800,"resampled phrase duration");float peak=0;for(float x:output->stereo)peak=std::max(peak,std::abs(x));check(peak>.05,"export contains sampled signal");std::remove(path);}
    check(realtimeAllocations==0&&realtimeDeallocations==0,"sample callbacks allocate and free nothing");
    std::printf("Sampler tests passed: %d assertions; no realtime allocations or frees.\n",assertions);
}
