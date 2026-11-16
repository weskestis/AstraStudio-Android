#include "WarpVoice.h"
#include "Project.h"
#include <signalsmith-stretch/signalsmith-stretch.h>
namespace astra::project {
struct WarpVoice::State {
    static constexpr int block=128;
    signalsmith::stretch::SignalsmithStretch<double> stretch{0x41535452};
    std::array<std::vector<double>,2> in;
    std::array<std::array<double,block>,2> out{};
    const sampler::Kernel* kernel{};
    double rate{},sourceRatio{},origin{},end{},anchorBeat{},anchorPhase{},framesPerBeat{},bpm{},consumed{};
    int band{},cursor{block},available{},latency{};
    int64_t generated{},first{},last{};
    bool identity{};SampleCursor reader;
    void read(const Sample& sample,double position,int count) noexcept {
        for(int i=0;i<count;++i){const double p=(position+i)*sourceRatio;
            const auto value=p<end&&p>=first?kernel->read(sample,reader,p,first,last,band,sample.rate==rate):fx::Stereo{};
            in[0][i]=value.l;in[1][i]=value.r;
        }
    }
    void process(const Sample& sample,const TempoMap* map,int count) noexcept {
        const double seconds=(generated+count)/rate;
        const double beat=map?map->advance(anchorBeat,seconds,bpm):anchorBeat+seconds*bpm/60;
        const double target=origin+(beat-anchorBeat)*framesPerBeat+stretch.inputLatency();
        // Maximum supported speed is 300/30 = 10; the buffer also holds seek history.
        const int input=static_cast<int>(std::clamp(std::round(target-consumed),0.0,double(in[0].size())));
        read(sample,consumed,input);stretch.process(in,input,out,count);
        consumed+=input;generated+=count;cursor=0;available=count;
    }
};
WarpVoice::WarpVoice() noexcept=default;
WarpVoice::~WarpVoice()=default;
bool WarpVoice::prepare(double rate,const sampler::Kernel* kernel) noexcept {
    if(!kernel)return false;
    try {
        if(state_&&state_->rate==rate&&state_->kernel==kernel)return true;
        auto s=std::make_unique<State>();s->rate=rate;s->kernel=kernel;
        s->stretch.presetDefault(2,rate,true);s->latency=s->stretch.outputLatency();
        for(auto& channel:s->in)channel.resize(std::max(s->stretch.inputLatency(),State::block*10+2));
        state_=std::move(s);return true;
    }catch(const std::bad_alloc&){return false;}
}
void WarpVoice::start(const Clip& clip,const Sample& sample,double beat,double phase,double offset,double bpm,const TempoMap* map) noexcept {
    auto& s=*state_;s.bpm=bpm;s.anchorBeat=beat;s.anchorPhase=phase;
    s.sourceRatio=sample.rate/s.rate;s.band=sampler::Kernel::bandFor(s.sourceRatio);
    s.first=clip.first+clip.warpStart;s.last=clip.last;
    s.framesPerBeat=s.rate*60/clip.sourceBpm;
    s.origin=(s.first+offset)/s.sourceRatio+phase*s.framesPerBeat;
    s.end=std::min(double(s.last),s.first+offset+clip.length*60/clip.sourceBpm*sample.rate);
    s.generated=0;s.cursor=State::block;s.available=0;
    // Preserve samples exactly when no timing/pitch transform is necessary.
    s.identity=clip.pitch==0&&(!map||!map->enabled)&&bpm==clip.sourceBpm;
    s.consumed=s.origin;
    if(s.identity)return;
    s.stretch.reset();s.stretch.setTransposeSemitones(clip.pitch);
    const int history=s.stretch.inputLatency();s.read(sample,s.origin,history);
    const double speed=(map?map->bpmAt(beat,bpm):bpm)/clip.sourceBpm;
    s.stretch.seek(s.in,history,speed);s.consumed+=history;
    // Known media permits lookahead: discard only the algorithm's pre-roll,
    // leaving the very next output aligned to the requested musical position.
    for(int left=s.latency;left>0;){const int n=std::min(left,State::block);s.process(sample,map,n);left-=n;}
    s.cursor=s.available;
}
fx::Stereo WarpVoice::tick(const Sample& sample,const TempoMap* map) noexcept {
    auto& s=*state_;
    if(s.identity){const double p=s.consumed*s.sourceRatio;s.consumed+=1;
        return p<s.end?s.kernel->read(sample,s.reader,p,s.first,s.last,s.band,sample.rate==s.rate):fx::Stereo{};}
    if(s.cursor>=s.available)s.process(sample,map,State::block);
    const int at=s.cursor++;const double l=s.out[0][at],r=s.out[1][at];
    return {std::isfinite(l)?static_cast<float>(l):0.f,std::isfinite(r)?static_cast<float>(r):0.f};
}
}
