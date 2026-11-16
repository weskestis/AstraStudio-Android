#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include "project/WaveWriter.h"
#include <bit>
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
void check(bool value,const char* label){++checks;if(!value){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
struct Temp {char path[32]="/tmp/astra-master-XXXXXX";Temp(){const int fd=mkstemp(path);check(fd>=0,"temporary WAV");close(fd);}~Temp(){std::remove(path);}};
auto tone(int rate,double hz){auto s=std::make_shared<Sample>();s->rate=rate;s->stereo.resize(rate*2);
    for(int i=0;i<rate;++i){const float x=static_cast<float>(.25*std::sin(2*std::numbers::pi*hz*i/rate));s->stereo[i*2]=x;s->stereo[i*2+1]=-x;}return s;}
std::vector<double> audioWire(int64_t frames,double beats=1){return {1,1,4,4,0,0,16,1,1,0,beats,0,0,double(frames),0,0,1,0,0,beats,0,0};}
std::vector<double> midiWire(){return {1,1,4,4,0,0,16,1,1,0,1,-1,0,0,1,0,0,1,69,1,1,0,0,1,0,0};}
std::array<float,kMixValues> mix(){std::array<float,kMixValues> m{};m[0]=120;m[1]=.8;m[3]=80;for(int t=0;t<16;++t)m[4+t*6]=.8;return m;}
double magnitude(const Sample& s,double hz){double a=0,b=0;const int first=s.rate/10,last=static_cast<int>(s.frames())-s.rate/10;
    for(int i=first;i<last;++i){const double phase=2*std::numbers::pi*hz*i/s.rate;a+=s.stereo[i*2]*std::cos(phase);b+=s.stereo[i*2]*std::sin(phase);}return 2*std::hypot(a,b)/(last-first);}
void header(const char* path,int rate,int bits,int64_t frames){auto* f=std::fopen(path,"rb");check(f!=nullptr,"open exported WAV");std::array<unsigned char,44> b{};check(std::fread(b.data(),1,b.size(),f)==b.size(),"complete RIFF header");
    auto u16=[&](int at){return unsigned(b[at])|(unsigned(b[at+1])<<8);};auto u32=[&](int at){return u16(at)|(u16(at+2)<<16);};
    check(u16(20)==(bits==24?1:3)&&u16(22)==2&&u16(34)==unsigned(bits),"actual stereo PCM24 / float32 encoding");
    check(u32(24)==unsigned(rate)&&u16(32)==unsigned(bits/4)&&u32(28)==unsigned(rate*bits/4),"sample rate, alignment and byte rate agree");
    check(u32(40)==frames*bits/4&&u32(4)==36+frames*bits/4,"RIFF sizes describe the exact render duration");
    check(fseeko(f,0,SEEK_END)==0&&ftello(f)==44+frames*bits/4,"physical WAV size matches header");std::fclose(f);
}
}
int main(){
    std::atomic<bool> cancel{false};std::atomic<float> progress{};auto m=mix();std::array<synth::Patch,astra::kTrackCount> patches{};std::array<fx::Config,astra::kTrackCount> effects{};
    Samples samples{};samples[0]=tone(48000,55);const auto wire=audioWire(samples[0]->frames());
    for(int rate:kExportRates){Temp f;check(renderWave(f.path,wire,samples,patches,effects,m,rate,.125f,cancel,progress,false)==0,"supported rate renders PCM24");header(f.path,rate,24,rate/2+static_cast<int64_t>(std::ceil(rate*.125)));check(progress==1,"render progress reaches one");}
    {Temp f;for(int rate:{0,12345,384000})check(renderWave(f.path,wire,samples,patches,effects,m,rate,0,cancel,progress,false)==1,"unsupported master rate rejected");
        auto tooLong=wire;tooLong[19]=65536;check(renderWave(f.path,tooLong,samples,patches,effects,m,192000,0,cancel,progress,false)==1,"oversized RIFF rejected before writing");}
    {Temp pcm,floating;check(renderWave(pcm.path,wire,samples,patches,effects,m,192000,0,cancel,progress,false)==0,"192 kHz PCM24 master");
        check(renderWave(floating.path,wire,samples,patches,effects,m,192000,0,cancel,progress,true)==0,"192 kHz float reference");
        header(pcm.path,192000,24,96000);header(floating.path,192000,32,96000);
        auto a=readSample(pcm.path,kSampleBudget),b=readSample(floating.path,kSampleBudget);check(a&&b&&magnitude(*a,55)>.1,"48 kHz source preserves bass pitch after conversion to 192 kHz");
        int lowBits=0;for(std::size_t i=0;i<a->stereo.size();++i){check(std::abs(a->stereo[i]-b->stereo[i])<=1.6/8388608,"PCM24 differs only by final quantization/dither");if((static_cast<int>(std::lrint(a->stereo[i]*8388608.0))&255)!=0)++lowBits;}
        check(lowBits>10000,"24-bit master retains precision beyond 16 bits");}
    {Temp source,out;auto high=tone(192000,30000);{WaveWriter writer(source.path,192000,high->frames(),true);check(writer.write(high->stereo)&&writer.finish(),"high-rate streamed source fixture");}
        Samples streamed{};streamed[0]=readSample(source.path,kPlaybackBudget,true);check(streamed[0]&&streamed[0]->stream,"high-rate source uses bounded disk pages");
        check(renderWave(out.path,audioWire(high->frames()),streamed,patches,effects,m,192000,0,cancel,progress,false)==0,"192 kHz engine renders streamed source");
        auto result=readSample(out.path,kSampleBudget);check(result&&magnitude(*result,30000)>.1,"30 kHz fixture survives native-rate render, rather than a 48 kHz render relabeled or upsampled");}
    {auto filter=std::make_unique<synth::Filter>();for(int internalRate:{96000,384000,768000,96000}){
        check(filter->prepare(static_cast<float>(internalRate)),"comb buffers prepared off render thread for rate changes");filter->configure(4,20,.7f,0,0,static_cast<float>(internalRate));
        inRender=true;for(int i=0;i<64;++i)filter->process(0);
        int echo=-1;for(int i=0;i<internalRate/20+4;++i){const float x=filter->process(i==0?.5f:0.f);check(std::isfinite(x),"comb finite after changing preparation rate");if(i>0&&std::abs(x)>.01f&&echo<0)echo=i;}inRender=false;
        check(echo==internalRate/20,"20 Hz comb delay remains 50 ms at every prepared rate");}}
    {auto core=std::make_unique<RenderCore>();check(core->prepare(192000),"prepare full 192 kHz DSP graph");auto patch=synth::preset(1);patch[synth::Filter1Type]=4;patch[synth::Filter1Cutoff]=20;
        check(core->setPatch(0,patch),"publish high-rate comb patch");auto fx=fx::preset(0);for(int id:fx::enableIds)fx[id]=1;check(fx.valid()&&core->setEffects(0,fx),"all effect types enabled at 192 kHz");
        check(core->enqueue({CommandType::NoteOn,0,33,1,.7f}),"start high-rate bass voice");std::array<float,1024> block{};double power=0;
        inRender=true;for(int n=0;n<80;++n){core->render(block.data(),512);for(float x:block){check(std::isfinite(x)&&std::abs(x)<=fx::LookaheadLimiter::ceiling,"192 kHz instrument/effects/limiter output finite and bounded");power+=x*x;}}inRender=false;check(power>.001,"complete 192 kHz DSP path is audible");}
    {Temp f;check(renderWave(f.path,midiWire(),Samples{},patches,effects,m,192000,0,cancel,progress,false)==0,"MIDI synth master renders directly at 192 kHz");auto s=readSample(f.path,kSampleBudget);check(s&&magnitude(*s,440)>.001,"synth note keeps A4 tuning");}
    {auto kernel=std::make_unique<sampler::Kernel>();Sequencer seq;check(seq.prepare(192000,kernel.get()),"192 kHz Warp preparation");Plan p;p.mode=1;p.samples[0]=tone(48000,220);auto& c=p.clips[0];c.present=true;c.asset=0;c.last=p.samples[0]->frames();c.length=1;c.warp=true;c.sourceBpm=120;p.regions[0]={{1,0,0,1,0,0}};p.tempo={true,{{0,90,false},{.5,120,true}}};seq.activate(&p);
        Sample out;out.rate=192000;out.stereo.resize(192000);TempoClock clock;double beat=0;
        inRender=true;for(int i=0;i<96000;++i){std::array<fx::Stereo,astra::kTrackCount> audio{};seq.tick(beat,120,audio);out.stereo[2*i]=audio[0].l;out.stereo[2*i+1]=audio[0].r;beat=clock.tick(&p.tempo,beat,120,192000);}inRender=false;
        check(magnitude(out,220)>.15,"warped 48 kHz source retains pitch during 192 kHz tempo-map rendering");}
    cancel=true;{Temp f;check(renderWave(f.path,wire,samples,patches,effects,m,192000,0,cancel,progress,false)==3,"high-rate export cancellation");}
    check(realtimeAllocations==0&&realtimeDeallocations==0,"high-rate DSP, Warp and comb allocate/free nothing during render");
    std::printf("Master tests passed: %d checks; 192 kHz PCM24 verified; realtime allocations=%d frees=%d.\n",checks,realtimeAllocations.load(),realtimeDeallocations.load());
}
