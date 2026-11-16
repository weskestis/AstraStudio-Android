#include "project/Project.h"
#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include "project/StretchRender.h"
#include "project/WaveWriter.h"
#include <algorithm>
#include <atomic>
#include <bit>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <unistd.h>
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
std::atomic<int> realtimeFileReads{0};
extern "C" size_t __real_fread(void*,size_t,size_t,FILE*);
extern "C" int __real_fseeko(FILE*,off_t,int);
extern "C" size_t __wrap_fread(void* p,size_t a,size_t b,FILE* f){if(inRender)++realtimeFileReads;return __real_fread(p,a,b,f);}
extern "C" int __wrap_fseeko(FILE* f,off_t p,int w){if(inRender)++realtimeFileReads;return __real_fseeko(f,p,w);}
using namespace astra;
using namespace astra::project;
namespace {
int assertions=0;
void check(bool b,const char* message){++assertions;if(!b){std::fprintf(stderr,"FAIL: %s\n",message);std::exit(1);}}
std::string temp(){char path[]="/tmp/astra-stream-XXXXXX";int fd=mkstemp(path);check(fd>=0,"temporary file");close(fd);return path;}
float signal(int64_t i,int c){return float(.3*std::sin(2*3.141592653589793*(c?220:55)*i/48000));}
void fixture(const std::string& path,int frames,bool floating){WaveWriter writer(path.c_str(),48000,frames,floating);std::array<float,1024> b{};
    for(int at=0;at<frames;){int n=std::min(512,frames-at);for(int i=0;i<n;++i)for(int c=0;c<2;++c)b[i*2+c]=signal(at+i,c);check(writer.write({b.data(),std::size_t(n*2)}),"fixture block");at+=n;}check(writer.finish(),"fixture finalized");}
bool read(const Sample& s,int64_t at,float* out,int n,SampleCursor& cursor){inRender=true;const bool good=s.read(at,out,n,cursor);inRender=false;return good;}
void waitReady(const Sample& s,int64_t at,float* out,int n,SampleCursor& cursor){s.prefetch(at);for(int tries=0;tries<1000;++tries){if(read(s,at,out,n,cursor))return;std::this_thread::sleep_for(std::chrono::milliseconds(2));}check(false,"stream prefetch completed");}
std::vector<double> wire(int64_t first,int64_t last){return {1,1,4,4,0,0,16,1,1,0,1,0,double(first),double(last),0,0,1,0,0,1,0,0};}
std::array<float,kMixValues> mix(){std::array<float,kMixValues> m{};m[0]=120;m[1]=.7;m[3]=80;for(int i=0;i<16;++i)m[4+i*6]=.8;return m;}
}
int main(){
    const auto path=temp(),out=temp(),ref=temp();fixture(path,48000*35,true);
    auto resident=readSample(path.c_str(),kSampleBudget);auto streamed=readSample(path.c_str(),kPlaybackBudget,true);
    check(resident&&streamed&&streamed->stream&&streamed->stereo.empty(),"long file is streamed without full decoded vector");
    check(streamed->memoryBytes()==2*1024*1024&&resident->memoryBytes()>streamed->memoryBytes(),"page storage remains two MiB");
    check(streamed->frames()==resident->frames()&&streamed->peaks==resident->peaks,"all peaks and source metadata match resident scan");
    check(!readSample(path.c_str(),2*1024*1024-1,true),"cache allocation budget enforced");
    std::atomic<bool> cancel{true};check(!readSample(path.c_str(),kPlaybackBudget,true,&cancel),"large-file preparation cancellation");cancel=false;
    std::array<float,1024> block{};SampleCursor cursor;
    for(const int64_t at:{0LL,4090LL,450001LL,1200000LL,8190LL,240001LL}){
        waitReady(*streamed,at,block.data(),512,cursor);for(int i=0;i<1024;++i)check(block[i]==resident->stereo[at*2+i],"seek/page boundary samples are exact");}
    auto kernel=std::make_unique<sampler::Kernel>();
    for(double position:{4100.25,450050.75,1200100.5}){waitReady(*streamed,int64_t(position)-40,block.data(),128,cursor);
        inRender=true;const auto a=kernel->read(*streamed,cursor,position,0,streamed->frames(),sampler::Kernel::bandFor(2.3),false);inRender=false;
        const auto b=kernel->read(resident->stereo.data(),position,0,resident->frames(),sampler::Kernel::bandFor(2.3),false);check(a.l==b.l&&a.r==b.r,"streamed sinc is identical to resident sinc");}
    // Concurrent playback and offline reads have separate pages/file positions.
    auto offline=offlineSample(streamed);check(offline&&offline->stream&&offline.get()!=streamed.get(),"independent export reader");
    std::atomic<bool> done{false};std::thread exporter([&]{SampleCursor c;std::array<float,256> b{};for(int i=0;i<2000;++i){const int64_t at=(i*7907LL)%(resident->frames()-128);if(!offline->read(at,b.data(),128,c))std::abort();for(int n=0;n<256;++n)if(b[n]!=resident->stereo[at*2+n])std::abort();}done=true;});
    int successes=0;while(!done){const int64_t at=(successes*401LL)%20000;waitReady(*streamed,at,block.data(),128,cursor);for(int i=0;i<256;++i)check(block[i]==resident->stereo[at*2+i],"concurrent cache reads do not tear");++successes;}exporter.join();check(successes>0,"playback remained available during export reads");
    {std::array<SampleCursor,16> readers;std::array<sampler::Voice,16> voices,referenceVoices;
        for(int t=0;t<16;++t){const int64_t at=4096LL*(t*8+1);waitReady(*streamed,at,block.data(),512,readers[t]);
            sampler::Zone z;z.asset=0;z.first=at;z.last=at+1000;z.loopFirst=at;z.loopLast=z.last;z.loop=true;z.reverse=t%2;z.root=60;
            voices[t].start(streamed.get(),z,60,.7f,48000,false);referenceVoices[t].start(resident.get(),z,60,.7f,48000,false);}
        // Each loop occupies one page. Independent forward/reverse cursors share a file.
        for(int frame=0;frame<2200;++frame)for(int t=0;t<16;++t){inRender=true;const auto v=voices[t].tick(*kernel);inRender=false;
            const auto expected=referenceVoices[t].tick(*kernel);if(v.l!=expected.l||v.r!=expected.r)std::fprintf(stderr,"voice frame=%d track=%d got=%g,%g expected=%g,%g misses=%llu\n",frame,t,v.l,v.r,expected.l,expected.r,(unsigned long long)streamed->misses());check(v.l==expected.l&&v.r==expected.r,"sixteen looping forward/reverse streamed sampler voices match resident voices");}
    }
    Samples a{},b{};a[0]=streamed;b[0]=resident;std::array<synth::Patch,astra::kTrackCount> patches{};std::array<fx::Config,astra::kTrackCount> effects{};std::atomic<float> progress{0};
    const auto w=wire(450001,480001);const auto m=mix();
    check(renderWave(out.c_str(),w,a,patches,effects,m,48000,0,cancel,progress,true)==0,"streamed export completes");
    check(renderWave(ref.c_str(),w,b,patches,effects,m,48000,0,cancel,progress,true)==0,"resident reference export");
    auto rendered=readSample(out.c_str(),kSampleBudget),reference=readSample(ref.c_str(),kSampleBudget);check(rendered&&reference&&rendered->stereo==reference->stereo,"export never drops asynchronous cache misses");
    auto silentMix=m;silentMix[1]=0;check(renderWave(out.c_str(),w,a,patches,effects,silentMix,48000,0,cancel,progress,true,0)==0,"source bounce bypasses master");
    rendered=readSample(out.c_str(),kSampleBudget);check(rendered&&rendered->frames()==24000,"source bounce exact duration without rack/limiter latency");
    for(int i=0;i<48000;++i)check(rendered->stereo[i]==resident->stereo[450001*2+i],"source bounce keeps original pre-effect audio");
    check(stretchWave(out.c_str(),*offline,400000,424000,24000,0,cancel,progress)==0,"streamed identity stretch completes");rendered=readSample(out.c_str(),kSampleBudget);
    check(rendered&&std::equal(rendered->stereo.begin(),rendered->stereo.end(),resident->stereo.begin()+800000),"identity stretch preserves file samples");
    check(stretchWave(out.c_str(),*offline,400000,424000,36000,7,cancel,progress)==0,"streamed independent time/pitch stretch");
    fixture(ref,48000*4,false);auto pcm=readSample(ref.c_str(),kPlaybackBudget,true),pcmResident=readSample(ref.c_str(),kSampleBudget);check(pcm&&pcm->stream,"PCM24 stream");waitReady(*pcm,160000,block.data(),512,cursor);for(int i=0;i<1024;++i)check(block[i]==pcmResident->stereo[320000+i],"PCM24 streaming decode exact");
    // A sparse 600 MiB WAV exceeds the old whole-project decoded limit.
    const auto huge=temp();{const int64_t frames=600LL*1024*1024/8;WaveWriter writer(huge.c_str(),48000,frames,true);check(writer.good(),"large sparse WAV header");}
    check(truncate(huge.c_str(),600LL*1024*1024+44)==0,"sparse data extent");
    auto large=readSample(huge.c_str(),kPlaybackBudget,true);check(large&&large->frames()*8>int64_t(kSampleBudget)&&large->memoryBytes()==2*1024*1024,"600 MiB source uses two MiB audio pages");
    check(!readSample(huge.c_str(),kSampleBudget),"legacy full resident reader correctly rejects oversized source");
    waitReady(*large,large->frames()-512,block.data(),512,cursor);check(std::all_of(block.begin(),block.end(),[](float x){return x==0;}),"far EOF seek reads correct page");
    auto broken=offlineSample(large);check(truncate(huge.c_str(),44)==0,"truncate fixture after reader creation");check(!broken->read(500000,block.data(),512,cursor)&&broken->failed(),"read failures are exposed instead of successful silent export");
    check(renderWave(out.c_str(),wire(500000,530000),Samples{large},patches,effects,m,48000,0,cancel,progress,true)==2,"export fails on truncated media");
    cancel=true;check(renderWave(out.c_str(),w,a,patches,effects,m,48000,0,cancel,progress,true)==3,"streamed export cancellation");
    {auto core=std::make_unique<RenderCore>();check(core->prepare(48000),"reclamation renderer prepare");
        auto owned=std::make_shared<Sample>();owned->rate=48000;owned->stereo.resize(2048);std::weak_ptr<const Sample> weak=owned;
        Samples bank{};bank[0]=owned;check(core->setProject(wire(0,1024),bank),"publish owned sample plan");owned.reset();bank={};
        inRender=true;core->render(block.data(),512);inRender=false;
        const std::vector<double> empty{1,0,4,4,0,0,16,0,0};check(core->setProject(empty,bank),"replace with empty project");
        inRender=true;core->render(block.data(),512);inRender=false;
        check(!weak.expired(),"callback retains retired owner for control-thread destruction");core->reclaimRetiredPlans();check(weak.expired(),"retired source ownership promptly reclaimed off callback");
    }
    check(realtimeAllocations==0&&realtimeDeallocations==0,"no callback allocation/free while streaming and seeking");
    check(realtimeFileReads==0,"no file reads or seeks on real-time thread");
    std::printf("Streaming tests passed: %d assertions; realtime allocations=%d frees=%d file operations=%d; 600 MiB source / 2 MiB pages.\n",assertions,realtimeAllocations.load(),realtimeDeallocations.load(),realtimeFileReads.load());
    std::remove(path.c_str());std::remove(out.c_str());std::remove(ref.c_str());std::remove(huge.c_str());
}
