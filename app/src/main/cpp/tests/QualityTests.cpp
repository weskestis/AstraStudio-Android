#include "core/RenderCore.h"
#include "project/StretchRender.h"
#include "project/WaveWriter.h"
#include "project/OfflineRender.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <numbers>
#include <limits>
#include <unistd.h>
using namespace astra;
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
namespace {
int assertions{};
void check(bool value,const char* label){++assertions;if(!value){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
struct Temp {char path[32]="/tmp/astra-quality-XXXXXX";Temp(){const int fd=mkstemp(path);check(fd>=0,"temporary output");close(fd);}~Temp(){std::remove(path);}};
project::Sample tone(int rate,double hz,double seconds=2){project::Sample s;s.rate=rate;s.stereo.resize(static_cast<std::size_t>(rate*seconds)*2);
    for(int64_t i=0;i<s.frames();++i){s.stereo[i*2]=static_cast<float>(.5*std::sin(2*std::numbers::pi*hz*i/rate));s.stereo[i*2+1]=-s.stereo[i*2];}return s;}
std::vector<double> wire(const project::TempoMap& map){std::vector<double> w{3,1,4,4,0,0,16,1,1,0,8,-1,0,0,1,0,0,8,69,1,1,0,0,8,0,0};
    for(int t=0;t<16;++t){w.push_back(0);for(int z=0;z<17;++z)w.insert(w.end(),{-1,0,0,0,0,60,0,1,0,.002,.3,1,.05,0,0,0,0});}
    w.push_back(map.enabled);w.push_back(map.points.size());for(auto p:map.points)w.insert(w.end(),{p.beat,p.bpm,double(p.ramp)});return w;}
}
int main(){
    auto kernel=std::make_unique<sampler::Kernel>();
    {auto s=tone(48000,731);for(int i=0;i<1000;++i){const auto x=kernel->read(s.stereo.data(),i,0,s.frames(),0,true);check(x.l==s.stereo[i*2]&&x.r==s.stereo[i*2+1],"unity-rate audio remains bit-exact");}}
    {auto s=tone(96000,30000);double energy=0;const int band=sampler::Kernel::bandFor(2);
        for(int i=100;i<24000;++i){const auto x=kernel->read(s.stereo.data(),i*2,0,s.frames(),band,false);energy+=x.l*x.l;}
        const double db=20*std::log10(std::sqrt(energy/23900)/std::sqrt(.125));std::printf("96->48 kHz rejection of 30 kHz tone: %.2f dB\n",db);check(db< -75,"out-of-band audio rejected before downsampling");}
    {auto s=tone(44100,3000);double error=0,power=0;
        for(int i=100;i<48000;++i){const double position=i*44100.0/48000;const auto x=kernel->read(s.stereo.data(),position,0,s.frames(),0,false);const double expected=.5*std::sin(2*std::numbers::pi*3000*i/48000);error+=(x.l-expected)*(x.l-expected);power+=expected*expected;check(std::abs(x.l+x.r)<1e-7,"SRC stereo polarity preserved");}
        const double snr=10*std::log10(power/error);std::printf("44.1->48 kHz 3 kHz sine SNR: %.2f dB\n",snr);check(snr>85,"interpolated sinc phase accuracy");}
    {Temp f;std::array<float,8> values{1.5f,-1.5f,1e-8f,-1e-8f,0,0,.123456789f,-.123456789f};project::WaveWriter writer(f.path,48000,4,true);
        check(writer.good()&&writer.write(values)&&writer.finish(),"write float32 with headroom");auto s=project::readSample(f.path,1024);check(s&&std::equal(values.begin(),values.end(),s->stereo.begin()),"float WAV retains all samples without dither or quantization");}
    {Temp f;std::array<float,4> values{1.1f,-1.1f,0,0};project::WaveWriter writer(f.path,48000,2,false);check(writer.write(values)&&writer.finish(),"write clipped PCM24 safely");auto s=project::readSample(f.path,1024);check(s&&s->stereo[0]>.999&&s->stereo[1]<-.999,"PCM24 quantizer cannot wrap across full scale");}
    std::atomic<bool> cancel{};std::atomic<float> progress{};auto source=tone(48000,440);
    {Temp f;check(project::stretchWave(f.path,source,std::numeric_limits<int64_t>::min(),std::numeric_limits<int64_t>::max(),48000,0,cancel,progress)==1,"extreme trim values rejected before subtraction");}
    {Temp f;check(project::stretchWave(f.path,source,0,source.frames(),source.frames(),0,cancel,progress)==0,"identity stretch");auto s=project::readSample(f.path,project::kSampleBudget);check(s&&s->stereo==source.stereo,"identity stretch bypasses phase processing exactly");}
    for(double ratio:{.75,1.25,1.5})for(double shift:{0.0,7.0}){Temp f;const auto frames=static_cast<int64_t>(source.frames()*ratio);
        check(project::stretchWave(f.path,source,0,source.frames(),frames,shift,cancel,progress)==0,"pitch/time render succeeds");auto s=project::readSample(f.path,project::kSampleBudget);check(s&&s->frames()==frames&&progress==1,"latency trimmed to exact requested duration");
        int crossings=0;double power=0,stereoError=0;const int begin=12000,end=static_cast<int>(frames)-12000;
        for(int i=begin;i<end;++i){const float v=s->stereo[i*2];check(std::isfinite(v)&&std::abs(v)<2,"stretch output finite and bounded");stereoError=std::max(stereoError,double(std::abs(v+s->stereo[i*2+1])));if(v>0&&s->stereo[(i-1)*2]<=0)++crossings;power+=v*v;}
        std::printf("Stereo max opposite-phase error: %.8f\n",stereoError);check(stereoError<.001,"stereo phase relationship remains within -60 dB peak residual");
        const double frequency=crossings*48000.0/(end-begin),expected=440*std::exp2(shift/12);std::printf("Stretch %.2fx, shift %.0f: %.3f Hz (target %.3f), RMS %.4f\n",ratio,shift,frequency,expected,std::sqrt(power/(end-begin)));
        check(std::abs(frequency-expected)<2,"duration changes preserve chosen pitch");check(std::sqrt(power/(end-begin))>.25&&std::sqrt(power/(end-begin))<.45,"stretch gain stays near source without normalization");
    }
    for(int n:{128,1024,6000}){Temp f;auto shortSample=tone(48000,1000);shortSample.stereo.resize(n*2);check(project::stretchWave(f.path,shortSample,0,n,n*3/2,3,cancel,progress)==0,"short transient stretch completes");auto s=project::readSample(f.path,project::kSampleBudget);double power=0;for(float v:s->stereo){check(std::isfinite(v),"short stretch finite");power+=v*v;}check(s->frames()==n*3/2&&power>1e-5,"short stretched sound survives latency trimming");}
    {Temp a,b;check(project::stretchWave(a.path,source,10,96000,119988,3,cancel,progress)==0,"trimmed deterministic render");check(project::stretchWave(b.path,source,10,96000,119988,3,cancel,progress)==0,"repeat deterministic render");auto x=project::readSample(a.path,project::kSampleBudget),y=project::readSample(b.path,project::kSampleBudget);check(x&&y&&x->stereo==y->stereo,"repeat renders identical");cancel=true;check(project::stretchWave(a.path,source,0,96000,120000,0,cancel,progress)==3,"stretch cancellation");cancel=false;check(project::stretchWave(a.path,source,0,96001,120000,0,cancel,progress)==1,"reject out-of-source trim");check(project::stretchWave(a.path,source,0,96000,120000,std::numeric_limits<double>::quiet_NaN(),cancel,progress)==1,"reject nonfinite pitch");}
    project::TempoMap map{true,{{0,120,false},{4,60,false}}};
    check(map.valid()&&map.seconds(0,8,145)==6,"step tempo duration");check(map.advance(0,6,145)==8,"step tempo inverse");
    {auto ramp=map;ramp.points[0].ramp=true;const double expected=60*std::log(2.0)/15+4;check(std::abs(ramp.seconds(0,8,145)-expected)<1e-12,"ramp uses logarithmic beat integration");project::TempoClock clock;double beat=0;
        inRender=true;for(int i=0;i<48000*5;++i)beat=clock.tick(&ramp,beat,145,48000);inRender=false;
        check(std::abs(beat-ramp.advance(0,5,145))<1e-8,"sample clock follows analytic ramp");beat=clock.tick(&ramp,2,145,48000);check(std::abs(beat-ramp.advance(2,1.0/48000,145))<1e-12,"seek resets cached ramp");}
    {auto bad=map;bad.points[1].beat=0;check(!bad.valid(),"reject duplicate tempo points");bad=map;bad.points[0].beat=1;check(!bad.valid(),"require map origin");bad=map;bad.points[0].bpm=std::numeric_limits<double>::infinity();check(!bad.valid(),"reject nonfinite tempo");}
    {auto sample=std::make_shared<project::Sample>(tone(48000,731,7));project::Plan plan;plan.mode=1;plan.tempo=map;plan.samples[0]=sample;
        auto& clip=plan.clips[0];clip.present=true;clip.asset=0;clip.first=0;clip.last=sample->frames();clip.length=8;
        // Split at beat 5 (three seconds), retaining sample-exact source offset.
        plan.regions[0]={{1,0,0,5,0,0},{2,0,5,3,0,144000}};
        project::Sequencer sequencer;sequencer.prepare(48000,kernel.get());sequencer.activate(&plan);project::TempoClock clock;double beat=0;
        inRender=true;for(int frame=0;frame<288000;++frame){std::array<fx::Stereo,astra::kTrackCount> audio{};sequencer.tick(beat,145,audio);check(std::abs(audio[0].l-sample->stereo[frame*2])<1e-6,"raw audio stays continuous across tempo and sample-exact split");beat=clock.tick(&map,beat,145,48000);}inRender=false;
        sequencer.stop();std::array<fx::Stereo,astra::kTrackCount> audio{};inRender=true;sequencer.tick(6,145,audio);inRender=false;
        check(std::abs(audio[0].l-sample->stereo[192000*2])<1e-6,"seek after tempo boundary restores exact sample position");
    }
    {auto w=wire(map);check(project::decodePlan(w,{})!=nullptr,"version 3 tempo wire decodes");auto bad=w;bad.back()=2;check(!project::decodePlan(bad,{}),"reject nonboolean ramp");Temp f;
        std::array<synth::Patch,astra::kTrackCount> patches{};std::array<fx::Config,astra::kTrackCount> effects{};std::array<float,project::kMixValues> mix{};mix[0]=145;mix[1]=.7;mix[3]=80;for(int t=0;t<16;++t)mix[4+t*6]=.8;
        check(project::renderWave(f.path,w,{},patches,effects,mix,48000,0,cancel,progress,true)==0,"tempo map float export");auto audio=project::readSample(f.path,project::kSampleBudget);check(audio&&audio->frames()==288000,"export duration includes tempo changes");
        auto core=std::make_unique<RenderCore>();check(core->setProject(w,{}),"live tempo map");core->enqueue({CommandType::Transport,0,0,0,1});std::array<float,1024> output{};
        inRender=true;for(int i=0;i<375;++i)core->render(output.data(),512);inRender=false;
        check(std::abs(core->snapshot().beatMicro/1e6-6)<.0001,"live engine crosses tempo boundary at correct frame");check(core->snapshot().bpm==60,"live tempo published");
    }
    check(realtimeAllocations==0&&realtimeDeallocations==0,"tempo and SRC do not allocate or free in callbacks");
    std::printf("Quality tests passed: %d assertions.\n",assertions);
}
