#pragma once
#include "fx/FxPrimitives.h"
#include "core/Limits.h"
#include "sampler/Sampler.h"
#include "TempoMap.h"
#include "WarpVoice.h"
#include "SampleStream.h"
#include <atomic>
#include <span>
#include <string>
#include <vector>
namespace astra::project {
constexpr int kTracks=kTrackCount,kScenes=kSceneCount,kClips=kClipCount,kAssets=64,kMaxRegions=kRegionCount,kMaxNotes=256,kMaxPoints=64;
constexpr uint32_t kSequenceId=0x40000000U;
using Samples=std::array<std::shared_ptr<const Sample>,kAssets>;
struct Note {double start{},length{};int pitch{};float velocity{};};
struct Event {double beat{};int note{};bool on{};};
struct Point {double beat{};float value{};};
struct Clip {
    bool present{};double length{4};int asset{-1};int64_t first{},last{};uint64_t hash{};
    bool warp{};double sourceBpm{120},pitch{};int64_t warpStart{};
    std::vector<Note> notes;std::vector<Event> events;std::array<std::vector<Point>,4> lanes;
};
struct Region {int id{},slot{};double start{},length{},offset{};int64_t audioOffset{};bool operator==(const Region&)const=default;};
struct FrozenTrack {int asset{-1};int64_t musicFrames{};bool operator==(const FrozenTrack&)const=default;};
struct Plan {
    int mode{},numerator{4},denominator{4};bool loop{};double loopStart{},loopEnd{16};
    std::array<Clip,kClips> clips;std::array<std::vector<Region>,kTracks> regions;Samples samples;std::array<sampler::Instrument,kTracks> instruments;
    TempoMap tempo;std::array<FrozenTrack,kTracks> frozen;
    std::array<std::array<int,2>,kTracks> tableAssets;
    const synth::ImportedWavetable* table(int track,int osc)const noexcept{const int id=tableAssets[track][osc];return id<0?nullptr:samples[id]->wavetable.get();}
    bool decode(std::span<const double> data,const Samples& audio);
    double barBeats()const noexcept{return numerator*4.0/denominator;}
    double endBeat()const noexcept;
};
std::unique_ptr<Plan> decodePlan(std::span<const double>,const Samples&) noexcept;
struct Snapshot {std::array<int,kTracks> active{},queued{};std::array<float,kTracks> phase{};int mode{};};
// Callbacks are plain function pointers into RenderCore. No ownership or allocation
// crosses the render boundary; immutable Plan slots retire on the control thread.
class Sequencer {
public:
    using NoteFn=void(*)(void*,int,int,float,uint32_t,bool) noexcept;
    using AutoFn=void(*)(void*,int,int,float,bool) noexcept;
    void bind(void* owner,NoteFn note,AutoFn automation)noexcept{owner_=owner;note_=note;automation_=automation;}
    bool prepare(double rate,const sampler::Kernel* kernel=nullptr)noexcept;
    void activate(const Plan* next)noexcept;
    void stop(bool clearLaunches=false)noexcept;
    void rewind()noexcept{resetFrozen();stop();for(auto& t:tracks_){t.launchBeat=0;t.queued=-2;}}
    void launch(int track,int scene,double beat,double quantum)noexcept;
    void scene(int scene,double beat,double quantum)noexcept;
    void tick(double beat,float bpm,std::array<fx::Stereo,kTracks>& audio)noexcept;
    void renderFrozen(double beat,float bpm,bool playing,std::array<fx::Stereo,kTracks>& audio)noexcept;
    void resetFrozen()noexcept{for(auto& f:frozen_)f={};}
    bool loop(double& beat)const noexcept;
    double beatUnit()const noexcept{return plan_?4.0/plan_->denominator:1;}
    double barBeats()const noexcept{return plan_?plan_->barBeats():4;}
    Snapshot snapshot()const noexcept;
private:
    struct Track {int scene{-1},queued{-2};double launchBeat{},due{},previous{-1};int slot{-1},region{-1};uint64_t hash{};
        std::size_t cursor{};std::array<uint32_t,kMaxNotes> held{};double audioPosition{},warpBpm{},previousAbsolute{},lastAudioOffset{};int audioBand{};float phase{};SampleCursor reader;
        std::array<float,4> automation{};std::array<bool,4> automated{};};
    void release(int track)noexcept;void clearAutomation(int track)noexcept;
    void enter(int track,int slot,int region,double phase,double audioOffset,double elapsed,float bpm)noexcept;
    void event(int track,const Clip& clip,const Event& e)noexcept;
    uint32_t nextId()noexcept;
    const sampler::Kernel* kernel_{};const Plan* plan_{};std::array<Track,kTracks> tracks_{};double rate_{48000},previousBeat_{-1};uint32_t id_{kSequenceId};int automationClock_{};
    struct FrozenReader {SampleCursor reader;double position{},step{};int band{};bool active{};};
    std::array<FrozenReader,kTracks> frozen_;
    std::array<WarpVoice,kTracks> warps_;
    void* owner_{};NoteFn note_{};AutoFn automation_{};
};
} // namespace astra::project
