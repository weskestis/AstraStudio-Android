#include "project/Project.h"
#include "project/OfflineRender.h"
#include "core/RenderCore.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>
#include <unistd.h>
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
using namespace astra;
using namespace astra::project;
namespace {
int assertions=0;
void check(bool v,const char* text){++assertions;if(!v){std::fprintf(stderr,"FAIL: %s\n",text);std::exit(1);}}
struct Wire {
    std::vector<double> v{1,0,4,4,0,0,16,0,0};
    Wire& clip(int slot,double length,std::vector<double> notes={},std::vector<double> points={},int asset=-1,int64_t first=0,int64_t last=0){
        ++v[7];v.insert(v.end(),{static_cast<double>(slot),length,static_cast<double>(asset),static_cast<double>(first),static_cast<double>(last),static_cast<double>(notes.size()/4),static_cast<double>(points.size()/3)});
        v.insert(v.end(),notes.begin(),notes.end());v.insert(v.end(),points.begin(),points.end());return *this;
    }
    Wire& region(int id,int slot,double at,double length,double offset=0,int64_t audioOffset=0){++v[8];v.insert(v.end(),{static_cast<double>(id),static_cast<double>(slot),at,length,offset,static_cast<double>(audioOffset)});return *this;}
};
struct Log {
    struct Entry {int track{},pitch{};uint32_t id{};bool on{};double beat{};};
    std::array<Entry,4096> notes{};std::size_t count{};double beat{};
    std::array<float,4> values{};std::array<bool,4> enabled{};
    static void note(void* ptr,int track,int key,float,uint32_t id,bool on)noexcept{auto& l=*static_cast<Log*>(ptr);if(l.count<l.notes.size())l.notes[l.count++]={track,key,id,on,l.beat};}
    static void automation(void* ptr,int,int lane,float value,bool enabled)noexcept{auto& l=*static_cast<Log*>(ptr);l.values[lane]=value;l.enabled[lane]=enabled;}
    void bind(Sequencer& s){s.bind(this,note,automation);static const auto kernel=std::make_unique<sampler::Kernel>();s.prepare(48000,kernel.get());}
    std::array<fx::Stereo,astra::kTrackCount> tick(Sequencer& s,double at,float bpm=120){beat=at;std::array<fx::Stereo,astra::kTrackCount> out{};inRender=true;s.tick(at,bpm,out);inRender=false;return out;}
};
void render(RenderCore& c,float* output,int frames){inRender=true;c.render(output,frames);inRender=false;}
std::string temp(){char name[]="/tmp/astra-project-XXXXXX";int fd=mkstemp(name);check(fd>=0,"temp file");close(fd);return name;}
void write(const std::string& path,const std::vector<unsigned char>& bytes){auto* f=std::fopen(path.c_str(),"wb");check(f!=nullptr,"open fixture");check(std::fwrite(bytes.data(),1,bytes.size(),f)==bytes.size(),"write fixture");std::fclose(f);}
std::vector<unsigned char> pcm16(){std::vector<unsigned char> b(48);std::memcpy(b.data(),"RIFF",4);b[4]=40;std::memcpy(b.data()+8,"WAVEfmt ",8);b[16]=16;b[20]=1;b[22]=1;b[24]=0x80;b[25]=0xbb;b[28]=0;b[29]=0x77;b[30]=1;b[32]=2;b[34]=16;std::memcpy(b.data()+36,"data",4);b[40]=4;b[44]=0xff;b[45]=0x7f;b[46]=0;b[47]=0x80;return b;}
}
int main(){
    const Samples empty{};
    Wire midi;midi.clip(0,2,{0,.5,41,.8,1,.25,48,.5},{0,0,.3,0,2,1.3,2,0,1000,2,2,5000});
    auto plan=decodePlan(midi.v,empty);check(plan&&plan->clips[0].events.size()==4,"decode MIDI events");
    for(std::size_t i=0;i<midi.v.size();++i){auto invalid=midi.v;invalid[i]=std::numeric_limits<double>::quiet_NaN();check(!decodePlan(invalid,empty),"reject every nonfinite wire field");}
    {auto bad=midi.v;bad.push_back(0);check(!decodePlan(bad,empty),"reject trailing wire data");bad.pop_back();bad.pop_back();check(!decodePlan(bad,empty),"reject truncation");}
    {Wire w;w.clip(0,4).clip(0,4);check(!decodePlan(w.v,empty),"duplicate clip slots rejected");}
    {Wire w;w.clip(0,4).region(1,0,0,4).region(2,0,3,4);check(!decodePlan(w.v,empty),"overlapping regions rejected");}
    {Wire w;w.clip(0,4,{3.9,.2,127,1});check(!decodePlan(w.v,empty),"notes cannot overrun clip");}
    {Wire w;w.clip(0,4,{}, {0,1,.5,0,0,.5});check(!decodePlan(w.v,empty),"automation strictly ordered");}
    {Sequencer s;Log l;l.bind(s);s.activate(plan.get());s.launch(0,0,.25,1);l.tick(s,.999);check(l.count==0,"quantized launch waits");l.tick(s,1);check(l.count==1&&l.notes[0].on&&l.notes[0].pitch==41,"launch at exact beat boundary");const auto id=l.notes[0].id;check(id>=kSequenceId,"sequence note IDs reserved");
        l.tick(s,1.5);check(l.count==2&&!l.notes[1].on&&l.notes[1].id==id,"sample clock note off retains identity");l.tick(s,2);check(l.count==3&&l.notes[2].pitch==48,"next note event");l.tick(s,3);check(l.notes[l.count-1].on&&l.notes[l.count-1].pitch==41,"clip loop retriggers");
        check(l.enabled[0]&&l.enabled[2],"automation enabled");s.stop();check(!l.enabled[0]&&!l.enabled[2],"stop restores automation");check(!l.notes[l.count-1].on,"stop releases held note");
    }
    {Wire w;w.clip(0,4,{0,2,41,1}).clip(1,4,{0,2,45,1});auto p=decodePlan(w.v,empty);Sequencer s;Log l;l.bind(s);s.activate(p.get());s.scene(0,.3,4);l.tick(s,3.999);check(l.count==0,"scene waits for bar");l.tick(s,4);check(l.count==2&&l.notes[0].beat==l.notes[1].beat,"scene tracks start together");s.launch(0,-1,4.1,.5);l.tick(s,4.49);check(s.snapshot().active[0]==0,"quantized stop waits");l.tick(s,4.5);check(s.snapshot().active[0]==-1&&s.snapshot().active[1]==1,"stop only selected track");
        auto same=decodePlan(w.v,empty);const auto count=l.count;s.activate(same.get());check(l.count==count,"unchanged plan preserves held notes");
    }
    {Wire w;w.v[1]=1;w.clip(0,4,{0,2,41,1,3,.5,48,1},{1,0,-1,1,4,1}).region(1,0,2,4);auto p=decodePlan(w.v,empty);Sequencer s;Log l;l.bind(s);s.activate(p.get());l.tick(s,1);check(l.count==0,"arrangement silent before region");l.tick(s,3);check(l.count==1&&l.notes[0].pitch==41,"seek chases sustained MIDI");check(std::abs(l.values[1]+.5f)<1e-5,"linear automation at seek");l.tick(s,4);check(!l.notes[l.count-1].on,"chased note ends correctly");l.tick(s,5);check(l.notes[l.count-1].pitch==48,"later MIDI event");l.tick(s,6);check(s.snapshot().active[0]==-1&&!l.enabled[1],"region end stops notes and automation");
        p->loop=true;p->loopStart=2;p->loopEnd=6;double beat=6.25;check(s.loop(beat)&&beat==2.25,"arrangement loop preserves fractional overshoot");
    }
    {auto sample=std::make_shared<Sample>();sample->rate=48000;sample->stereo.resize(48000);for(int i=0;i<24000;++i){sample->stereo[i*2]=i/24000.f;sample->stereo[i*2+1]=-i/24000.f;}Samples audio{};audio[0]=sample;
        Wire w;w.v[1]=1;const int split=12345;const double cut=split/24000.0;w.clip(0,1,{}, {},0,0,24000).region(1,0,0,cut).region(2,0,cut,1-cut,0,split);auto p=decodePlan(w.v,audio);check(p!=nullptr,"sample precise split accepted");Sequencer s;Log l;l.bind(s);s.activate(p.get());
        for(int i=0;i<24000;++i){const auto out=l.tick(s,i/24000.0);check(std::abs(out[0].l-sample->stereo[i*2])<1e-6&&std::abs(out[0].r-sample->stereo[i*2+1])<1e-6,"stereo split is continuous at original sample positions");}
        check(l.tick(s,1)[0].l==0,"audio region end silent");
        sample->rate=24000;Sequencer resampled;Log r;r.bind(resampled);resampled.activate(p.get());for(int n=0;n<=100;++n)r.tick(resampled,n/24000.0);const auto y=r.tick(resampled,101/24000.0)[0];check(std::abs(y.l-50.5f/24000)<1e-7,"band-limited interpolation follows an interior ramp");
    }
    {auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(midi.v,empty)&&core->setProject(midi.v,empty)&&core->setProject(midi.v,empty),"three immutable plan slots");check(!core->setProject(midi.v,empty),"plan publication backpressure");render(*core,out.data(),512);check(core->setProject(midi.v,empty),"retired plan reused off render thread");core->enqueue({CommandType::Transport,0,0,0,1});core->enqueue({CommandType::LaunchClip,0,0,0,0});render(*core,out.data(),512);check(core->sequenceSnapshot().active[0]==0,"RenderCore launches compiled clip");core->panic();render(*core,out.data(),512);check(core->snapshot().activeVoices==0&&!core->snapshot().playing,"panic clears sequence and voices");
        std::atomic<bool> finished{false};std::thread producer([&]{for(int i=0;i<1000;++i)while(!core->setProject(midi.v,empty))std::this_thread::yield();finished=true;});while(!finished)render(*core,out.data(),64);producer.join();render(*core,out.data(),512);check(core->snapshot().droppedCommands==0,"concurrent project snapshots publish safely");
    }
    {const auto path=temp();auto bytes=pcm16();write(path,bytes);auto a=readSample(path.c_str(),16);check(a&&a->frames()==2&&a->stereo[0]==32767/32768.f&&a->stereo[1]==a->stereo[0]&&a->stereo[2]==-1,"PCM16 mono duplicated to stereo");check(!readSample(path.c_str(),15),"decoded sample budget enforced before allocation");bytes.pop_back();write(path,bytes);check(!readSample(path.c_str(),1000),"truncated audio rejected");bytes=pcm16();bytes[32]=0;write(path,bytes);check(!readSample(path.c_str(),1000),"invalid block alignment rejected");std::remove(path.c_str());}
    {Wire w;w.v[1]=1;w.clip(0,1,{0,.25,41,.7}).region(1,0,0,1);std::array<synth::Patch,astra::kTrackCount> patches{};std::array<fx::Config,astra::kTrackCount> effects{};std::array<float,kMixValues> mix{};mix[0]=120;mix[1]=.7;mix[3]=80;for(int t=0;t<16;++t)mix[4+t*6]=.8;
        std::atomic<bool> cancel{false};std::atomic<float> progress{0};const auto path=temp();check(renderWave(path.c_str(),w.v,empty,patches,effects,mix,48000,.1f,cancel,progress)==0,"offline WAV renders");auto audio=readSample(path.c_str(),kSampleBudget);check(audio&&audio->rate==48000&&audio->frames()==28800,"WAV duration includes rounded requested tail");check(progress==1,"export progress complete");
        auto core=std::make_unique<RenderCore>();core->prepare(48000);core->setProject(w.v,empty);core->enqueue({CommandType::Tempo,0,0,0,120});core->enqueue({CommandType::MasterGain,0,0,0,.7});core->enqueue({CommandType::LimiterRelease,0,0,0,80});for(int t=0;t<16;++t){core->setPatch(t,patches[t]);core->setEffects(t,effects[t]);core->enqueue({CommandType::TrackGain,t,0,0,.8});}
        std::array<float,1024> preroll{};for(int i=0;i<20;++i)render(*core,preroll.data(),512);core->enqueue({CommandType::Transport,0,0,0,1});std::vector<float> live(20000);render(*core,live.data(),10000);const int latency=core->outputLatencyFrames();
        for(int i=0;i<9000;++i)check(std::abs(audio->stereo[i]-live[latency*2+i])<4.f/8388608,"export matches live DSP with known latency trimmed");
        cancel=true;check(renderWave(path.c_str(),w.v,empty,patches,effects,mix,48000,0,cancel,progress)==3,"export cancellation");cancel=false;check(renderWave(path.c_str(),w.v,empty,patches,effects,mix,12345,0,cancel,progress)==1,"invalid export rate rejected");std::remove(path.c_str());
    }
    {Wire w;w.v[1]=1;w.clip(0,4,{0,.125,60,1}).region(1,0,0,4);auto dry=std::make_unique<RenderCore>(),wet=std::make_unique<RenderCore>();for(auto* c:{dry.get(),wet.get()}){c->setProject(w.v,empty);c->enqueue({CommandType::Tempo,0,0,0,120});}wet->enqueue({CommandType::SendB,0,0,0,1});std::array<float,1024>a{},b{};for(int i=0;i<20;++i){render(*dry,a.data(),512);render(*wet,b.data(),512);}dry->enqueue({CommandType::Transport,0,0,0,1});wet->enqueue({CommandType::Transport,0,0,0,1});double difference=0;for(int n=0;n<80;++n){render(*dry,a.data(),512);render(*wet,b.data(),512);if(n>30)for(int i=0;i<1024;++i)difference+=std::abs(a[i]-b[i]);}check(difference>1,"shared echo send produces an audible delayed return");wet->panic();render(*wet,b.data(),512);check(std::all_of(b.begin(),b.end(),[](float x){return x==0;}),"panic clears send tails");}
    check(realtimeAllocations.load()==0,"no allocation in sequencer/render callback");check(realtimeDeallocations.load()==0,"no deallocation in sequencer/render callback");
    std::printf("Project tests passed: %d assertions; no realtime allocations or frees.\n",assertions);
}
