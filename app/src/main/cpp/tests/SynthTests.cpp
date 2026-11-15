#include "core/RenderCore.h"
#include "dsp/SynthVoice.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <memory>
#include <numbers>
#include <thread>
#include <vector>

extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations;
namespace {
int checks = 0;
void check(bool yes, const char* what) {
    ++checks;
    if (!yes) { std::fprintf(stderr, "FAIL: %s\n", what); std::exit(1); }
}
void render(astra::RenderCore& core, float* out, int n) {
    inRender = true; core.render(out, n); inRender = false;
}
double energy(const std::vector<float>& v, int first, int last) {
    double result = 0;
    for (int i = first; i < last; ++i) result += v[i] * v[i];
    return result / (last - first);
}
double difference(const std::vector<float>& a, const std::vector<float>& b) {
    double result = 0;
    for (std::size_t i = 0; i < a.size(); ++i) result += (a[i]-b[i]) * (a[i]-b[i]);
    return result / a.size();
}
std::vector<float> phrase(const astra::synth::Patch& patch, int note = 41, int count = 12000, double sampleRate = 48000) {
    auto core = std::make_unique<astra::RenderCore>();
    core->prepare(sampleRate);
    check(core->setPatch(0, patch), "accept complete valid patch");
    core->enqueue({astra::CommandType::NoteOn, 0, note, 1, .8f});
    std::vector<float> out(static_cast<std::size_t>(count)*2);
    for (int i = 0; i < count; i += 127) render(*core, out.data()+2*i, std::min(127,count-i));
    return out;
}
double crossings(const std::vector<float>& a, int sampleRate) {
    int count = 0;
    const int first = sampleRate / 10;
    for (int i = first+1; i < static_cast<int>(a.size())/2; ++i) if (a[2*i] > 0 && a[2*i-2] <= 0) ++count;
    return count * sampleRate / (static_cast<double>(a.size())/2 - first);
}
}
int main() {
    using namespace astra;
    using namespace astra::synth;
    for (int presetIndex = 0; presetIndex < kPresetCount; ++presetIndex) {
        auto p = preset(presetIndex);
        check(p.valid(), "factory preset validation");
        std::array<float, kWireCount> wire{}, roundtrip{};
        p.encode(wire); Patch decoded;
        check(decoded.decode(wire), "wire decode"); decoded.encode(roundtrip);
        check(wire == roundtrip, "lossless patch wire roundtrip");
        wire[0] = .5f; check(!decoded.decode(wire), "fractional instrument rejected");
        wire[0] = 1e30f; check(!decoded.decode(wire), "huge enum rejected before int conversion");
        wire[0] = std::numeric_limits<float>::quiet_NaN(); check(!decoded.decode(wire), "NaN patch rejected");
        p.encode(wire); wire.back() = 1.1f; check(!decoded.decode(wire), "out of range route rejected");
        p.encode(wire); wire[kWireCount-3] = 1e30f; check(!decoded.decode(wire), "huge route enum rejected");
        check(!decoded.decode(std::span<const float>(wire).first(kWireCount-1)), "truncated patch rejected");
    }
    {
        auto bank = std::make_unique<WavetableBank>();
        constexpr int n = 4096; std::vector<float> samples(n);
        const float mip = WavetableBank::mipFor(1500,48000);
        for (int i = 0; i < n; ++i) samples[i] = bank->read(0, 2.0f/3, i * 1500.0/48000.0, mip);
        double wanted = 0, unwanted = 0;
        for (int bin = 1; bin < n/2; ++bin) {
            double real = 0, imag = 0;
            for (int i = 0; i < n; ++i) {
                const double angle = 2 * std::numbers::pi * bin * i / n;
                real += samples[i] * std::cos(angle); imag -= samples[i] * std::sin(angle);
            }
            const double e = real*real + imag*imag;
            if (bin % 128 == 0 && bin / 128 <= 13) wanted += e; else unwanted += e;
        }
        check(wanted > 1 && unwanted < wanted * 1e-8, "mip saw has no folded high harmonics in coherent spectrum");
        for (int bankIndex = 0; bankIndex < 4; ++bankIndex) for (int pos = 0; pos <= 12; ++pos)
            for (int i = 0; i < 256; ++i) {
                const float value = bank->read(bankIndex,pos/12.0f,i/256.0f,3.2f);
                check(std::isfinite(value) && std::abs(value) < 1.2f,"all wavetable families interpolate within bounds");
            }
    }
    {
        auto p = preset(0); auto normal = phrase(p,69,48000);
        p[OscATune]=12; auto octave = phrase(p,69,48000);
        check(std::abs(crossings(normal,48000)-440) < 3,"A4 tuning");
        check(std::abs(crossings(octave,48000)-880) < 3,"oscillator octave tuning");
        p=preset(0);p[OscALevel]=0;p[SubLevel]=1;auto sub=phrase(p,69,48000);
        check(std::abs(crossings(sub,48000)-220)<3,"sub is one octave below note");
        p=preset(0);p[OscALevel]=0;p[NoiseLevel]=.5f;auto noise=phrase(p);
        check(energy(noise,2000,24000)>1e-5,"noise oscillator audible");
        check(noise==phrase(p),"noise seed and output deterministic");
    }
    std::vector<std::vector<float>> previews;
    for(int i=0;i<kPresetCount;++i) {
        auto p=preset(i);auto out=phrase(p);
        check(energy(out,2000,24000)>1e-7,"factory preset is audible");
        for(float x:out)check(std::isfinite(x)&&std::abs(x)<=.988554f,"factory output finite and bounded");
        for(const auto& previous:previews)check(difference(previous,out)>1e-7,"factory presets produce distinct signals");
        previews.push_back(std::move(out));
    }
    {
        auto p=preset(0);p[OscBLevel]=.7f;p[OscBBank]=1;p[OscBPosition]=.5f;
        auto dry=phrase(p);
        p[FmAmount]=.8f;check(difference(dry,phrase(p))>1e-5,"A to B FM affects output");
        p[FmAmount]=0;
        for(int warp=1;warp<=3;++warp){p[OscAWarp]=static_cast<float>(warp);p[OscAWarpAmount]=.6f;check(difference(dry,phrase(p))>1e-6,"sync and bend modes affect output");}
    }
    {
        auto p=preset(5);p.routes.fill({});p[Filter1Type]=0;p[SubLevel]=0;
        std::vector<std::vector<float>> algorithms;
        for(int algorithm=0;algorithm<4;++algorithm){
            p[Algorithm]=static_cast<float>(algorithm);auto out=phrase(p);
            for(const auto& a:algorithms)check(difference(out,a)>1e-6,"FM algorithms differ");
            algorithms.push_back(out);
            for(int op=0;op<4;++op){auto edited=p;edited[Op1Level+op*6]=0;check(difference(out,phrase(edited))>1e-8,"each FM operator reaches output");}
        }
    }
    {
        auto p=preset(0);p[Env1Attack]=.001f;p.routes[0]={1,static_cast<int>(Target::Amplitude),.9f};
        auto core=std::make_unique<RenderCore>();core->setPatch(0,p);
        core->enqueue({CommandType::Tempo,0,0,0,120});core->enqueue({CommandType::NoteOn,0,57,1,1});
        std::vector<float> out(96000);for(int i=0;i<48000;i+=128)render(*core,out.data()+2*i,std::min(128,48000-i));
        check(energy(out,23000,25000)>energy(out,47000,49000)*20,"quarter-note LFO amplitude alternates peak and trough at 120 BPM");
        auto bright=preset(0);bright[OscAPosition]=.66f;core->setPatch(0,bright);
        render(*core,out.data(),4096);check(core->snapshot().activeVoices==1,"live patch editing keeps held voice");
        bright[Output]=0;core->setPatch(0,bright);render(*core,out.data(),4096);
        check(energy(out,7000,8192)<1e-12,"live gain edit reaches the existing voice without retrigger");
    }
    {
        const auto dry=phrase(preset(0));
        for(int source=1;source<=9;++source){auto p=preset(0);p.routes[0]={source,static_cast<int>(Target::Amplitude),.8f};
            check(difference(dry,phrase(p))>1e-7,"all four LFOs, three envelopes, velocity and key sources affect DSP");}
        auto p=preset(0);p.routes[0]={1,static_cast<int>(Target::Amplitude),.9f};p.shapes[0].fill(-1);
        auto low=phrase(p);p.shapes[0].fill(1);auto high=phrase(p);
        check(energy(high,2000,24000)>energy(low,2000,24000)*200,"drawn LFO shape reaches the audio engine");
    }
    for(double sr:{8000.,44100.,48000.,96000.,384000.}) for(int mode=0;mode<=5;++mode) {
        auto filter=std::make_unique<Filter>();
        for(float cutoff:{20.f,500.f,18000.f}) {
            filter->configure(mode,cutoff,.95f,1,.5f,static_cast<float>(sr));
            for(int i=0;i<4096;++i){float x=filter->process(i==0?1.f:0.f);check(std::isfinite(x)&&std::abs(x)<16,"filter impulse bounded at sample-rate and parameter extremes");}
        }
    }
    for (double sr:{44100.,48000.,96000.}) for(int i=0;i<kPresetCount;++i) {
        auto core=std::make_unique<RenderCore>();core->prepare(sr);core->setPatch(0,preset(i));
        core->enqueue({CommandType::NoteOn,0,29,1,.9f});core->enqueue({CommandType::NoteOff,0,0,1,0,static_cast<uint64_t>(sr*.1)});
        std::array<float,512> out{};
        for(int n=0;n<static_cast<int>(sr*1.5);n+=256)render(*core,out.data(),256);
        check(core->snapshot().activeVoices==0,"every preset release retires at each sample rate");
        check(std::all_of(out.begin(),out.end(),[](float x){return x==0;}),"release tail reaches exact silence");
    }
    {
        auto core=std::make_unique<RenderCore>(); std::array<float,512> out{};
        for(int i=0;i<64;++i)check(core->setPatch(i%16,preset(i%8)),"fill patch mailbox");
        check(!core->setPatch(0,preset(0)),"mailbox backpressure");
        render(*core,out.data(),256);
        for(int i=0;i<64;++i)check(core->setPatch(i%16,preset(i%8)),"mailbox slots recycled");
        core->panic();render(*core,out.data(),256);
        for(int i=0;i<64;++i)check(core->setPatch(i%16,preset(i%8)),"panic releases queued patch slots");
        render(*core,out.data(),256);
    }
    {
        auto core=std::make_unique<RenderCore>(); std::atomic<bool> done{false};
        std::thread producer([&]{for(int i=0;i<1000;++i){auto p=preset(i%8);while(!core->setPatch(i%16,p))std::this_thread::yield();}done.store(true,std::memory_order_release);});
        std::array<float,256> out{};
        do {render(*core,out.data(),128);}while(!done.load(std::memory_order_acquire));
        producer.join();render(*core,out.data(),128);
        check(core->snapshot().droppedCommands==0,"concurrent full-patch publication respects backpressure");
    }
    {
        auto core=std::make_unique<RenderCore>();core->setPatch(0,preset(2));std::array<float,256> out{};
        for(int i=0;i<128;++i)core->enqueue({CommandType::NoteOn,0,29+i%60,static_cast<uint32_t>(i+1),.4f});
        render(*core,out.data(),128);check(core->snapshot().activeVoices==128,"bounded 128-voice synth pool");
        core->panic();render(*core,out.data(),128);check(core->snapshot().activeVoices==0,"synth panic clears all voices");
    }
    {
        auto silent=preset(0);silent[Output]=0;
        auto core=std::make_unique<RenderCore>();core->setPatch(0,silent);core->panic();
        std::array<float,4096> out{};render(*core,out.data(),2048);
        core->enqueue({CommandType::NoteOn,0,57,1,1});render(*core,out.data(),2048);
        check(std::all_of(out.begin(),out.end(),[](float x){return x==0;}),"panic preserves accepted patch rather than diverging from UI");
    }
    {
        auto core=std::make_unique<RenderCore>();std::array<float,4096> out{};
        for(int batch=0;batch<32;++batch){
            for(int i=0;i<256;++i)core->enqueue({CommandType::NoteOn,0,57,static_cast<uint32_t>(1+batch*256+i),.8f,1000000});
            render(*core,out.data(),1);
        }
        auto silent=preset(0);silent[Output]=0;check(core->setPatch(0,silent),"accept patch ahead of heap overflow");
        render(*core,out.data(),1);render(*core,out.data(),1);
        core->enqueue({CommandType::NoteOn,0,57,1,1});render(*core,out.data(),2048);
        check(std::all_of(out.begin(),out.end(),[](float x){return x==0;}),"heap recovery retains latest accepted synth edit");
    }
    check(realtimeAllocations==0,"no heap allocation during synth render, preset dispatch, voice stealing or panic");
    std::printf("PASS: %d synthesis assertions; tuning, spectra, presets, modulation, filters, release, patch concurrency and realtime allocation\n",checks);
}
