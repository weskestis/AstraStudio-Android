#include "Project.h"
#include <algorithm>
#include <bit>
#include <cstdio>
#include <cstring>
#include <limits>
namespace astra::project {
namespace {
struct Reader {
    std::span<const double> data;std::size_t at{};bool ok{true};
    double get(double lo,double hi){if(at>=data.size()){ok=false;return lo;}const double v=data[at++];if(!std::isfinite(v)||v<lo||v>hi){ok=false;return lo;}return v;}
    int integer(int lo,int hi){const double v=get(lo,hi);if(v!=std::floor(v))ok=false;return static_cast<int>(v);}
    int64_t frame(){const double v=get(0,static_cast<double>(kMaxSourceFrames));if(v!=std::floor(v))ok=false;return static_cast<int64_t>(v);}
};
void hash(uint64_t& h,double x){h^=std::bit_cast<uint64_t>(x);h*=1099511628211ULL;}
}
bool Plan::decode(std::span<const double> data,const Samples& audio){
    if(data.size()>400000)return false;
    Reader r{data};const int version=r.integer(1,4);
    mode=r.integer(0,1);numerator=r.integer(1,12);denominator=r.integer(2,8);if(denominator!=2&&denominator!=4&&denominator!=8)return false;
    loop=r.integer(0,1)!=0;loopStart=r.get(0,65535);loopEnd=r.get(.0625,65536);if(loopEnd<=loopStart)return false;
    const int clipCount=r.integer(0,kClips),regionCount=r.integer(0,kMaxRegions);samples=audio;
    for(auto& c:clips)c=Clip{};
    for(auto& list:regions)list.clear();
    for(int n=0;n<clipCount&&r.ok;++n){const int slot=r.integer(0,kClips-1);auto& c=clips[slot];if(c.present)return false;c.present=true;
        c.length=r.get(.0625,65536);c.asset=r.integer(-1,kAssets-1);c.first=r.frame();c.last=r.frame();const int notes=r.integer(0,kMaxNotes),points=r.integer(0,kMaxPoints*4);
        if(c.asset>=0&&(!samples[c.asset]||c.last<=c.first||c.last>samples[c.asset]->frames()||notes))return false;
        c.hash=1469598103934665603ULL;hash(c.hash,c.length);hash(c.hash,c.asset);hash(c.hash,static_cast<double>(c.first));hash(c.hash,static_cast<double>(c.last));
        for(int i=0;i<notes&&r.ok;++i){Note note;note.start=r.get(0,c.length);note.length=r.get(1.0/960,c.length);note.pitch=r.integer(0,127);note.velocity=static_cast<float>(r.get(.001,1));
            if(note.start>=c.length||note.start+note.length>c.length+1e-8)return false;
            c.notes.push_back(note);c.events.push_back({note.start,i,true});c.events.push_back({note.start+note.length,i,false});
            hash(c.hash,note.start);hash(c.hash,note.length);hash(c.hash,note.pitch);hash(c.hash,note.velocity);
        }
        std::sort(c.events.begin(),c.events.end(),[](const Event& a,const Event& b){return a.beat==b.beat?(a.on==b.on?a.note<b.note:!a.on):a.beat<b.beat;});
        constexpr double low[4]={0,-1,20,0},high[4]={2,1,18000,1};
        for(int i=0;i<points&&r.ok;++i){const int lane=r.integer(0,3);Point p;p.beat=r.get(0,c.length);p.value=static_cast<float>(r.get(low[lane],high[lane]));
            auto& list=c.lanes[lane];if(list.size()>=kMaxPoints||(!list.empty()&&p.beat<=list.back().beat))return false;list.push_back(p);
            hash(c.hash,lane);hash(c.hash,p.beat);hash(c.hash,p.value);
        }
    }
    std::array<int,kMaxRegions> ids{};
    for(int i=0;i<regionCount&&r.ok;++i){Region x;x.id=r.integer(1,1000000000);x.slot=r.integer(0,kClips-1);x.start=r.get(0,65536);x.length=r.get(1.0/960,65536);x.offset=r.get(0,65536);x.audioOffset=r.frame();
        if(!clips[x.slot].present||x.start+x.length>65536)return false;
        for(int j=0;j<i;++j)if(ids[j]==x.id)return false;
        ids[i]=x.id;
        const auto& c=clips[x.slot];if(c.asset>=0&&x.audioOffset>=c.last-c.first)return false;
        regions[x.slot%16].push_back(x);
    }
    for(auto& list:regions){std::sort(list.begin(),list.end(),[](const Region& a,const Region& b){return a.start<b.start;});double end=0;
        for(const auto& x:list){if(x.start<end-1e-8)return false;end=x.start+x.length;}}
    instruments={};
    if(version>=2){
        auto zone=[&](sampler::Zone& z){
            z.asset=r.integer(-1,63);z.first=r.frame();z.last=r.frame();z.loopFirst=r.frame();z.loopLast=r.frame();z.root=r.integer(0,127);
            z.pitch=static_cast<float>(r.get(-48,48));z.gain=static_cast<float>(r.get(0,2));z.pan=static_cast<float>(r.get(-1,1));
            z.attack=static_cast<float>(r.get(0,5));z.decay=static_cast<float>(r.get(.00099999,30));z.sustain=static_cast<float>(r.get(0,1));z.release=static_cast<float>(r.get(.00499999,10));
            z.reverse=r.integer(0,1);z.loop=r.integer(0,1);z.oneShot=r.integer(0,1);z.choke=r.integer(0,8);
            if(z.asset<0)return z.first==0&&z.last==0&&z.loopFirst==0&&z.loopLast==0&&!z.loop;
            return samples[z.asset]&&z.last>z.first&&z.last<=samples[z.asset]->frames()&&z.loopFirst>=z.first&&z.loopLast<=z.last&&z.loopLast>z.loopFirst&&!(z.loop&&z.oneShot);
        };
        for(auto& instrument:instruments){instrument.mode=r.integer(0,2);if(!zone(instrument.sample))return false;for(auto& pad:instrument.pads)if(!zone(pad))return false;}
    }
    if(version>=3){tempo.enabled=r.integer(0,1)!=0;const int count=r.integer(0,128);tempo.points.reserve(count);
        for(int i=0;i<count;++i)tempo.points.push_back({r.get(0,65536),r.get(30,300),r.integer(0,1)!=0});
        if(!tempo.valid())return false;
    }
    if(version>=4){const int count=r.integer(0,kClips);std::array<bool,kClips> seen{};
        for(int i=0;i<count&&r.ok;++i){const int slot=r.integer(0,kClips-1);auto& c=clips[slot];
            if(seen[slot]||!c.present||c.asset<0)return false;
            seen[slot]=true;
            c.warp=r.integer(0,1);c.sourceBpm=r.get(30,300);c.warpStart=r.frame();c.pitch=r.get(-24,24);
            if(c.warpStart>=c.last-c.first)return false;
            hash(c.hash,c.warp);hash(c.hash,c.sourceBpm);hash(c.hash,double(c.warpStart));hash(c.hash,c.pitch);
        }
    }
    if(!r.ok||r.at!=data.size())return false;
    for(const auto& c:clips)if(c.present&&c.asset>=0)samples[c.asset]->prefetch(c.first+c.warpStart);
    for(const auto& instrument:instruments){const auto warm=[&](const sampler::Zone& z){if(z.asset>=0)samples[z.asset]->prefetch(z.reverse?z.last-1:z.first);};warm(instrument.sample);for(const auto& pad:instrument.pads)warm(pad);}
    return true;
}
std::unique_ptr<Plan> decodePlan(std::span<const double> data,const Samples& samples)noexcept{
    try{auto p=std::make_unique<Plan>();if(p->decode(data,samples))return p;}catch(const std::bad_alloc&){}return {};
}
double Plan::endBeat()const noexcept{double end=0;for(const auto& list:regions)if(!list.empty())end=std::max(end,list.back().start+list.back().length);return end;}
uint32_t Sequencer::nextId()noexcept{if(++id_>=0x7ffffffeU)id_=kSequenceId;return id_;}
bool Sequencer::prepare(double rate,const sampler::Kernel* kernel)noexcept{
    rate_=rate;kernel_=kernel;
    if(kernel)for(auto& warp:warps_)if(!warp.prepare(rate,kernel))return false;
    return true;
}
void Sequencer::release(int track)noexcept{auto& t=tracks_[track];if(note_)for(auto& id:t.held)if(id){note_(owner_,track,0,0,id,false);id=0;}t.slot=t.region=-1;t.previous=-1;t.cursor=0;}
void Sequencer::clearAutomation(int track)noexcept{auto& t=tracks_[track];for(int lane=0;lane<4;++lane)if(t.automated[lane]){t.automated[lane]=false;if(automation_)automation_(owner_,track,lane,0,false);}}
void Sequencer::activate(const Plan* next)noexcept{
    for(int i=0;i<16;++i){auto& t=tracks_[i];if(!next||!plan_||next->mode!=plan_->mode||(next->mode==1&&next->regions[i]!=plan_->regions[i])||
        (t.slot>=0&&(!next->clips[t.slot].present||next->clips[t.slot].hash!=t.hash||
            (plan_->clips[t.slot].asset>=0&&next->samples[plan_->clips[t.slot].asset]!=plan_->samples[plan_->clips[t.slot].asset])||
            (plan_->clips[t.slot].warp&&(next->tempo.enabled!=plan_->tempo.enabled||next->tempo.points!=plan_->tempo.points))))){release(i);clearAutomation(i);}}
    plan_=next;automationClock_=0;
}
void Sequencer::stop(bool clear)noexcept{for(int i=0;i<16;++i){release(i);clearAutomation(i);tracks_[i].phase=0;if(clear){tracks_[i].scene=-1;tracks_[i].queued=-2;}}
    previousBeat_=-1;automationClock_=0;
}
void Sequencer::launch(int track,int sceneIndex,double beat,double quantum)noexcept{
    if(track<0||track>=16||sceneIndex< -1||sceneIndex>=8)return;
    if(plan_&&sceneIndex>=0){const auto& c=plan_->clips[sceneIndex*16+track];if(c.present&&c.asset>=0)plan_->samples[c.asset]->prefetch(c.first+c.warpStart);}
    auto& t=tracks_[track];t.queued=sceneIndex;
    t.due=quantum>0?std::ceil((beat-1e-9)/quantum)*quantum:beat;t.due=std::max(beat,t.due);
}
void Sequencer::scene(int s,double beat,double q)noexcept{for(int t=0;t<16;++t)launch(t,s,beat,q);}
void Sequencer::event(int track,const Clip& c,const Event& e)noexcept{
    auto& t=tracks_[track];auto& id=t.held[e.note];if(e.on){if(id&&note_)note_(owner_,track,0,0,id,false);id=nextId();const auto& n=c.notes[e.note];if(note_)note_(owner_,track,n.pitch,n.velocity,id,true);}
    else if(id){if(note_)note_(owner_,track,0,0,id,false);id=0;}
}
void Sequencer::enter(int track,int slot,int region,double phase,double audioOffset,double elapsed,float bpm)noexcept{
    release(track);auto& t=tracks_[track];const auto& c=plan_->clips[slot];t.slot=slot;t.region=region;t.hash=c.hash;t.previous=phase;
    if(c.asset>=0){const auto& a=*plan_->samples[c.asset];t.audioPosition=c.first+audioOffset+(elapsed>=0?elapsed:phase*60/bpm)*a.rate;t.audioBand=sampler::Kernel::bandFor(a.rate/rate_);}
    else{
        for(std::size_t n=0;n<c.notes.size();++n){const auto& x=c.notes[n];if(x.start<=phase+1e-10&&x.start+x.length>phase+1e-10)event(track,c,{x.start,static_cast<int>(n),true});}
        while(t.cursor<c.events.size()&&c.events[t.cursor].beat<=phase+1e-10)++t.cursor;
    }
}
bool Sequencer::loop(double& beat)const noexcept{if(plan_&&plan_->mode==1&&plan_->loop&&beat>=plan_->loopEnd){beat=plan_->loopStart+std::fmod(beat-plan_->loopEnd,plan_->loopEnd-plan_->loopStart);return true;}return false;}
void Sequencer::tick(double beat,float bpm,std::array<fx::Stereo,16>& audio)noexcept{
    if(!plan_)return;
    if(previousBeat_>=0&&beat<previousBeat_)for(int i=0;i<16;++i)release(i);
    previousBeat_=beat;
    for(int i=0;i<16;++i){auto& t=tracks_[i];int slot=-1,region=-1;double phase=0,audioOffset=0,elapsed=-1;
        if(plan_->mode==0){if(t.queued!=-2&&beat+1e-10>=t.due){release(i);t.scene=t.queued;t.queued=-2;t.launchBeat=t.due;}
            if(t.scene>=0){slot=t.scene*16+i;if(!plan_->clips[slot].present){slot=-1;t.scene=-1;}else phase=std::fmod(std::max(0.0,beat-t.launchBeat),plan_->clips[slot].length);}}
        else{const auto& list=plan_->regions[i];auto at=std::upper_bound(list.begin(),list.end(),beat+1e-10,[](double b,const Region& r){return b<r.start;});
            if(at!=list.begin()){--at;if(beat<at->start+at->length-1e-10){slot=at->slot;region=at->id;const auto& c=plan_->clips[slot];
                phase=std::fmod(std::max(0.0,beat-at->start+at->offset),c.length);audioOffset=static_cast<double>(at->audioOffset);
                }}}
        if(slot<0){if(t.slot>=0)release(i);clearAutomation(i);t.phase=0;continue;}
        const auto& c=plan_->clips[slot];bool entered=t.slot!=slot||t.region!=region||t.hash!=c.hash||phase+1e-8<t.previous||(c.warp&&t.warpBpm!=bpm);
        const bool continueWarp=c.warp&&t.slot==slot&&t.hash==c.hash&&t.warpBpm==bpm&&region>=0&&t.region>=0&&
            audioOffset==t.lastAudioOffset&&std::abs((phase-t.previous)-(beat-t.previousAbsolute))<1e-7;
        if(entered){if(c.asset>=0&&plan_->mode==1){const double cycleStart=beat-phase;elapsed=plan_->tempo.seconds(std::max(0.0,cycleStart),beat,bpm);if(cycleStart<0)elapsed+=-cycleStart*60/plan_->tempo.bpmAt(0,bpm);}
            enter(i,slot,region,phase,audioOffset,elapsed,bpm);}
        if(c.asset<0){while(t.cursor<c.events.size()&&c.events[t.cursor].beat<=phase+1e-10)event(i,c,c.events[t.cursor++]);}
        else{const auto& sample=*plan_->samples[c.asset];if(c.warp&&kernel_){const auto* map=plan_->mode==1?&plan_->tempo:nullptr;
                if(entered&&!continueWarp){warps_[i].start(c,sample,beat,phase,audioOffset,bpm,map);t.warpBpm=bpm;}
                audio[i]=warps_[i].tick(sample,map);
            }else if(kernel_)audio[i]=kernel_->read(sample,t.reader,t.audioPosition,c.first,c.last,t.audioBand,sample.rate==rate_);
            else {const auto at=static_cast<int64_t>(t.audioPosition);if(at>=c.first&&at<c.last){float pair[2]{};sample.read(at,pair,1,t.reader);audio[i]={pair[0],pair[1]};}}
            t.audioPosition+=sample.rate/rate_;}
        if(entered||automationClock_==0)for(int lane=0;lane<4;++lane){const auto& points=c.lanes[lane];const bool enabled=!points.empty();float value=0;
            if(enabled){auto at=std::upper_bound(points.begin(),points.end(),phase,[](double b,const Point& p){return b<p.beat;});
                if(at==points.begin())value=at->value;else if(at==points.end())value=points.back().value;else{const auto& a=*(at-1);value=a.value+static_cast<float>((phase-a.beat)/(at->beat-a.beat))*(at->value-a.value);}}
            if(enabled!=t.automated[lane]||(enabled&&value!=t.automation[lane])){t.automated[lane]=enabled;t.automation[lane]=value;if(automation_)automation_(owner_,i,lane,value,enabled);}}
        t.previous=phase;t.previousAbsolute=beat;t.lastAudioOffset=audioOffset;t.phase=static_cast<float>(phase/c.length);
    }automationClock_=(automationClock_+1)&31;
}
Snapshot Sequencer::snapshot()const noexcept{Snapshot s;s.mode=plan_?plan_->mode:0;for(int i=0;i<16;++i){s.active[i]=tracks_[i].slot;s.queued[i]=tracks_[i].queued;s.phase[i]=tracks_[i].phase;}return s;}
} // namespace astra::project
