#include "core/RenderCore.h"
#include "project/OfflineRender.h"
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
#include <cstdio>
#include <cstdlib>
#include <numbers>
#include <unistd.h>
using namespace astra;
namespace {
int assertions=0;
void check(bool condition,const char* label){++assertions;if(!condition){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
auto tone(){auto s=std::make_shared<project::Sample>();s->rate=48000;s->stereo.resize(96000);for(int i=0;i<48000;++i){s->stereo[2*i]=.2f*std::sin(2*std::numbers::pi*100*i/48000);s->stereo[2*i+1]=-s->stereo[2*i];}return s;}
sampler::Zone zone(){sampler::Zone z;z.asset=0;z.last=z.loopLast=48000;z.attack=0;z.sustain=1;return z;}
void append(std::vector<double>& w,const sampler::Zone& z){w.insert(w.end(),{double(z.asset),double(z.first),double(z.last),double(z.loopFirst),double(z.loopLast),double(z.root),z.pitch,z.gain,z.pan,z.attack,z.decay,z.sustain,z.release,double(z.reverse),double(z.loop),double(z.oneShot),double(z.choke)});}
std::vector<double> wire(const sampler::Zone& base,const std::array<sampler::Zone,3>& extra,int count=3){
    std::vector<double>w{8,1,4,4,0,0,16,1,1,49,1,-1,0,0,1,0,0,.5,60,1,1,49,0,1,0,0};
    for(int t=0;t<50;++t){w.push_back(t==49?1:0);append(w,t==49?base:sampler::Zone{});for(int p=0;p<16;++p)append(w,sampler::Zone{});}
    w.insert(w.end(),{0,0,0,0});w.insert(w.end(),100,-1);
    for(int t=0;t<50;++t)for(int row=0;row<17;++row){const bool selected=t==49&&row==0;const auto z=selected?base:sampler::Zone{};w.insert(w.end(),{double(z.fadeIn),double(z.fadeOut),double(z.crossfade),selected?double(count):0});if(selected)for(int i=0;i<count;++i){append(w,extra[i]);w.insert(w.end(),{double(extra[i].fadeIn),double(extra[i].fadeOut),double(extra[i].crossfade)});}}
    return w;
}
void render(RenderCore& core,std::array<float,1024>& out){inRender=true;core.render(out.data(),512);inRender=false;}
}
int main(){auto kernel=std::make_unique<sampler::Kernel>();auto source=tone();project::Samples sources{};sources[0]=source;const auto z=zone();std::array<sampler::Zone,3> extra{};
    {sampler::Voice single;sampler::LayerVoice stack;single.start(source.get(),z,60,1,48000,false);stack.start(sources,z,extra,60,1,48000,false);inRender=true;for(int i=0;i<48000;++i){const auto a=single.tick(*kernel),b=stack.tick(*kernel);check(a.l==b.l&&a.r==b.r,"one layer remains bit exact");}inRender=false;}
    extra.fill(z);extra[0].pitch=12;extra[1].pitch=-12;extra[1].gain=.3f;extra[2].reverse=true;extra[2].pan=-.7f;extra[2].attack=.02;extra[2].fadeIn=120;extra[2].fadeOut=480;
    {std::array<sampler::Voice,4> independent;sampler::LayerVoice stack;for(int i=0;i<4;++i)independent[i].start(source.get(),i==0?z:extra[i-1],64,.7,192000,false);stack.start(sources,z,extra,64,.7,192000,false);inRender=true;for(int i=0;i<24000;++i){fx::Stereo expected{};for(auto& v:independent)expected=expected+v.tick(*kernel);const auto actual=stack.tick(*kernel);check(expected.l==actual.l&&expected.r==actual.r,"four independent zones sum exactly at 192k");}stack.release(true);for(int i=0;i<12000;++i)stack.tick(*kernel);check(!stack.active(),"all layers release");inRender=false;}
    {auto constant=tone();std::fill(constant->stereo.begin(),constant->stereo.end(),.5f);auto edge=z;edge.fadeIn=1000;edge.fadeOut=1000;sampler::Voice v;v.start(constant.get(),edge,60,1,48000,false);float at100=0,at2000=0,atEnd=0;for(int i=0;i<48000;++i){const auto s=v.tick(*kernel);if(i==100)at100=s.l;if(i==2000)at2000=s.l;if(i==47999)atEnd=s.l;}check(std::abs(at100-.05)<1e-6&&at2000==.5f&&atEnd==0,"fades affect edges and preserve interior");}
    {auto ramp=tone();for(int i=0;i<48000;++i)ramp->stereo[2*i]=ramp->stereo[2*i+1]=(i%1000)/1000.f;auto loop=z;loop.first=loop.loopFirst=0;loop.last=loop.loopLast=1000;loop.loop=true;loop.crossfade=200;
        for(bool reverse:{false,true}){loop.reverse=reverse;sampler::Voice v;v.start(ramp.get(),loop,60,1,48000,false);float previous=0;double jump=0;std::array<float,800> cycle{};for(int i=0;i<4000;++i){const float x=v.tick(*kernel).l;check(std::isfinite(x)&&x>=0&&x<=1,"crossfade remains bounded");if(i>1100)jump=std::max(jump,double(std::abs(x-previous)));if(i>=1600&&i<2400)cycle[i-1600]=x;if(i>=2400&&i<3200)check(std::abs(x-cycle[i-2400])<1e-6,"overlap repeats at span minus crossfade");previous=x;}check(jump<.02,"crossfade removes discontinuous wrap");}
    }
    {auto loop=z;loop.last=loop.loopLast=2;loop.loop=true;loop.crossfade=1;loop.pitch=48;for(bool reverse:{false,true}){loop.reverse=reverse;sampler::Voice v;v.start(source.get(),loop,127,1,192000,false);for(int i=0;i<2000;++i)check(std::isfinite(v.tick(*kernel).l),"tiny loops and extreme pitch stay finite");}}
    {auto choked=z;choked.choke=1;choked.oneShot=true;auto kept=z;kept.choke=2;kept.oneShot=true;sampler::LayerVoice stack;stack.start(sources,choked,{kept,sampler::Zone{},sampler::Zone{}},60,1,48000,true);for(int i=0;i<100;++i)stack.tick(*kernel);stack.choke(1u<<1);for(int i=0;i<500;++i)stack.tick(*kernel);check(stack.active(),"choke keeps unrelated layers");stack.choke(1u<<2);for(int i=0;i<500;++i)stack.tick(*kernel);check(!stack.active(),"matching extra layer chokes");}
    auto data=wire(z,extra);check(project::decodePlan(data,sources)!=nullptr,"native v8 includes track 50 layers");
    {auto bad=data;bad.pop_back();check(!project::decodePlan(bad,sources),"truncated extension rejected");bad=data;const auto at=26+50*290+4+100+49*68;bad[at]=48001;check(!project::decodePlan(bad,sources),"fade over source length rejected");bad=data;bad[at+2]=24001;check(!project::decodePlan(bad,sources),"overlap over half loop rejected");bad=data;bad[at+3]=4;check(!project::decodePlan(bad,sources),"fifth layer rejected");bad=data;bad[at+4]=63;check(!project::decodePlan(bad,sources),"missing extra source rejected");bad=data;bad[at+4]=.5;check(!project::decodePlan(bad,sources),"fractional asset id rejected");}
    {auto second=tone();sources[1]=second;auto x=extra;x[0].asset=1;auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(wire(z,x),sources),"publish layered plan");core->enqueue({CommandType::NoteOn,49,60,1,1});render(*core,out);check(core->snapshot().activeVoices==1,"four layers occupy one MIDI voice");auto next=sources;next[1]=tone();check(core->setProject(wire(z,x),next),"publish replacement extra source");render(*core,out);check(core->snapshot().activeVoices==0,"retire voice when extra source ownership changes");}
    {auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(data,sources),"publish full pool");for(int i=0;i<128;++i)core->enqueue({CommandType::NoteOn,49,60,static_cast<uint32_t>(100+i),.1f});render(*core,out);check(core->snapshot().activeVoices==128,"layer stacks stay within MIDI voice limit");core->panic();render(*core,out);check(core->snapshot().activeVoices==0,"panic clears all sample layers");}
    {std::array<synth::Patch,kTrackCount> patches;std::array<fx::Config,kTrackCount> effects;std::array<float,project::kMixValues> mix{};mix[0]=120;mix[1]=.7;mix[3]=80;for(int t=0;t<50;++t)mix[4+t*6]=.8;
        char path[]="/tmp/astra-layer-XXXXXX";const int fd=mkstemp(path);check(fd>=0,"temporary export");close(fd);std::atomic<bool> cancel{};std::atomic<float> progress{};
        check(project::renderWave(path,data,sources,patches,effects,mix,192000,.1f,cancel,progress)==0,"track 50 layered export at 192k PCM24");auto audio=project::readSample(path,project::kSampleBudget);check(audio&&audio->rate==192000&&audio->frames()==115200,"192k export metadata");float peak=0;for(float x:audio->stereo)peak=std::max(peak,std::abs(x));check(peak>.02,"layered export contains sound");std::remove(path);
    }
    check(realtimeAllocations==0&&realtimeDeallocations==0,"sample layers allocate and free nothing in realtime");std::printf("Layer sampling passed: %d assertions.\n",assertions);
}
