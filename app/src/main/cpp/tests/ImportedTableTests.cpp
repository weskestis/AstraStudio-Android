#include "core/RenderCore.h"
#include "project/OfflineRender.h"
#include "dsp/ImportedWavetable.h"
#include <bit>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <numbers>
#include <unistd.h>
using namespace astra;
using namespace astra::project;
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations,realtimeDeallocations;
namespace {
int checks{};void check(bool ok,const char* label){++checks;if(!ok){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
struct Temp{char path[32]="/tmp/astra-table-XXXXXX";Temp(){int fd=mkstemp(path);check(fd>=0,"temp file");close(fd);}~Temp(){std::remove(path);}};
void word(std::ofstream& f,uint32_t x,int n){for(int i=0;i<n;++i)f.put(char(x>>(8*i)));}
void wave(const char* path,int cycle=2048,int frames=256){std::ofstream f(path,std::ios::binary);const std::string text="<!>"+std::to_string(cycle)+" ";const auto bytes=uint32_t(cycle*frames*4);const auto length=text.size()+(text.size()%2);
    f.write("RIFF",4);word(f,uint32_t(36+8+length+bytes),4);f.write("WAVEfmt ",8);word(f,16,4);word(f,3,2);word(f,1,2);word(f,44100,4);word(f,44100*4,4);word(f,4,2);word(f,32,2);
    f.write("clm ",4);word(f,uint32_t(text.size()),4);f.write(text.data(),text.size());if(text.size()%2)f.put(0);f.write("data",4);word(f,bytes,4);
    for(int j=0;j<frames;++j)for(int i=0;i<cycle;++i){const double phase=2*std::numbers::pi*i/cycle;const float x=float(1.5*(1.-double(j)/(frames+1))*std::sin(phase)+.2*std::sin(phase*31));word(f,std::bit_cast<uint32_t>(x),4);}}
std::vector<double> wire(int asset=0,int track=49){std::vector<double> w{7,1,4,4,0,0,16,1,1,double(track),2,-1,0,0,1,0,0,1.5,45,.7,1,double(track),0,2,0,0};
    for(int t=0;t<kTracks;++t){w.push_back(0);for(int z=0;z<17;++z)w.insert(w.end(),{-1,0,0,0,0,60,0,1,0,.005,.2,.8,.08,0,0,0,0});}
    w.insert(w.end(),{0,0,0,0});for(int t=0;t<kTracks;++t)w.insert(w.end(),{double(t==track?asset:-1),-1});return w;}
void block(RenderCore& core,float* out,int n){inRender=true;core.render(out,n);inRender=false;}
}
int main(int argc,char** argv){
    if(argc==2){int files=0,tables=0;uint64_t bytes=0;
        for(const auto& e:std::filesystem::recursive_directory_iterator(argv[1]))if(e.is_regular_file()&&e.path().extension()==".wav"){
            auto s=readSample(e.path().c_str(),kPlaybackBudget,true);check(bool(s),"supplied WAV decodes in actual native reader");++files;bytes+=e.file_size();if(s->wavetable){++tables;check(s->wavetable->frames()==256&&s->wavetable->cycle()==2048,"all imported original wavetable cycles retained");}
        }check(files>1000&&tables==6,"complete supplied pack audit");std::printf("Native pack audit passed: %d unique WAV files, %d original wavetables, %llu bytes.\n",files,tables,(unsigned long long)bytes);return 0;
    }
    Temp file;wave(file.path);auto source=readSample(file.path,kPlaybackBudget,true);check(source&&source->wavetable&&!source->stream,"wavetable prepares off-thread and stays resident");
    const auto& table=*source->wavetable;check(table.frames()==256&&table.cycle()==2048,"full 256 by 2048 resolution");
    check(source->memoryBytes()<=12*1024*1024,"decimated mip storage bounded");check(!readSample(file.path,source->memoryBytes()-1,true),"mips count toward memory budget");
    check(readSample(file.path,source->memoryBytes(),true)!=nullptr,"exact memory budget accepted");
    for(int f:{0,1,127,254,255})for(int i=0;i<2048;i+=37){const float actual=table.read(float(f)/255,double(i)/2048,0);check(std::abs(actual-source->stereo[(f*2048+i)*2])<.0001,"every source frame retained without normalization");}
    check(table.read(0,.25,0)>1.2f,"float headroom retained");
    for(int i=0;i<256;++i){const double p=double(i)/256;check(std::abs(table.read(.5f,p,0)-.5f*(table.read(127.f/255,p,0)+table.read(128.f/255,p,0)))<.0001,"position morphs between adjacent cycles");}
    double harmonic=0,fundamental=0;const float mip=table.mipFor(1500,96000);
    for(int i=0;i<2048;++i){const double phase=2*std::numbers::pi*i/2048;const double x=table.read(0,double(i)/2048,mip);fundamental+=x*std::sin(phase);harmonic+=x*std::sin(phase*31);}
    check(fundamental>1000&&std::abs(harmonic)<fundamental*1e-5,"upper mip removes harmonics above safe limit without losing fundamental");
    Samples samples{};samples[0]=source;auto w=wire();check(decodePlan(w,samples)!=nullptr,"custom table on Track 50 decodes");auto bad=w;bad[bad.size()-2]=63;check(!decodePlan(bad,samples),"missing custom table rejected");bad=w;bad.back()=.5;check(!decodePlan(bad,samples),"fractional table id rejected");
    auto core=std::make_unique<RenderCore>();std::array<float,1024> out{};check(core->setProject(w,samples),"publish imported table");core->enqueue({CommandType::NoteOn,49,45,123,.8f});double power=0;
    for(int i=0;i<50;++i){block(*core,out.data(),512);for(float v:out){check(std::isfinite(v),"table synth finite");power+=v*v;}}
    check(power>.001,"custom wavetable audible in native voice");
    check(core->setProject(wire(-1),samples),"switch back to factory safely");block(*core,out.data(),512);check(core->snapshot().activeVoices==0,"retire table pointers before old plan reclamation");core->reclaimRetiredPlans();
    std::array<synth::Patch,kTracks> patches{};std::array<fx::Config,kTracks> effects{};std::array<float,kMixValues> mix{};mix[0]=120;mix[1]=.7f;mix[3]=80;for(int t=0;t<kTracks;++t)mix[4+t*6]=.7f;
    std::atomic<bool> cancel{false};std::atomic<float> progress{};Temp master,other;
    check(renderWave(master.path,w,samples,patches,effects,mix,192000,.1f,cancel,progress,false)==0,"custom table exports 192 kHz PCM24");
    patches[49][synth::OscAPosition]=1;
    check(renderWave(other.path,w,samples,patches,effects,mix,192000,.1f,cancel,progress,false)==0,"edited custom table exports");
    auto a=readSample(master.path,kSampleBudget),b=readSample(other.path,kSampleBudget);check(a&&b&&a->rate==192000&&a->frames()==211200&&a->stereo!=b->stereo,"position edit changes real exported audio");
    check(realtimeAllocations==0&&realtimeDeallocations==0,"custom synthesis and retirement allocate/free nothing in callback");
    std::printf("Imported wavetable tests passed: %d checks; realtime allocations=%d frees=%d.\n",checks,realtimeAllocations.load(),realtimeDeallocations.load());
}
