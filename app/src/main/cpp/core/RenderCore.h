#pragma once
#include "SpscRing.h"
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace astra {
inline constexpr int kTrackCount = 16;
inline constexpr int kVoiceCount = 128;
inline constexpr uint64_t kImmediate = std::numeric_limits<uint64_t>::max();
inline constexpr std::size_t kQueueCapacity = 4096;
inline constexpr std::size_t kScheduledCapacity = 8192;

enum class CommandType : int32_t {
    NoteOn = 0, NoteOff = 1, MasterGain = 2, TrackGain = 3, TrackPan = 4,
    TrackMute = 5, TrackSolo = 6, Tempo = 7, Transport = 8, Metronome = 9
};
struct Command {
    CommandType type{};
    int32_t track{};
    int32_t note{};
    uint32_t noteId{};
    float value{};
    uint64_t frame{kImmediate};
    uint64_t order{};
};

struct CoreSnapshot {
    uint64_t frame{};
    uint64_t beatMicro{};
    uint32_t activeVoices{};
    uint32_t droppedCommands{};
    uint32_t lateCommands{};
    uint32_t clippedSamples{};
    float peakLeft{};
    float peakRight{};
    float bpm{145.0f};
    bool playing{};
};

// Control thread: constructor, prepare, enqueue, panic, snapshot.
// Audio thread only: render and every private DSP method.
// prepare must run before callbacks begin. No resize/prepare while streaming.
class RenderCore final {
public:
    RenderCore() noexcept;
    bool prepare(double sampleRate) noexcept;
    bool enqueue(Command command) noexcept;
    void panic() noexcept { panic_.store(true, std::memory_order_release); }
    void render(float* interleavedStereo, int32_t frames) noexcept;
    CoreSnapshot snapshot() const noexcept;
    static bool valid(const Command& command) noexcept;
private:
    struct Voice {
        uint32_t id{};
        int32_t track{};
        int32_t note{};
        double phase{};
        double increment{};
        float envelope{};
        float velocity{};
        float releaseStep{};
        bool active{};
        bool releasing{};
        uint64_t age{};
    };
    struct Track {
        float gain{0.8f};
        float pan{};
        float targetLeft{0.565685425f};
        float targetRight{0.565685425f};
        float left{0.565685425f};
        float right{0.565685425f};
        bool mute{};
        bool solo{};
    };
    struct Later {
        bool operator()(const Command& a, const Command& b) const noexcept {
            return a.frame != b.frame ? a.frame > b.frame : a.order > b.order;
        }
    };
    bool schedule(const Command& command) noexcept;
    Command popScheduled() noexcept;
    void consumeCommands() noexcept;
    void apply(const Command& command) noexcept;
    void noteOn(const Command& command) noexcept;
    void releaseVoice(Voice& voice) noexcept;
    void resetSound() noexcept;
    void updateTrackTargets() noexcept;
    float oscillator(double phase) const noexcept;
    void publish(float left, float right) noexcept;

    SpscRing<Command, kQueueCapacity> commands_;
    std::array<Command, kScheduledCapacity> scheduled_{};
    std::size_t scheduledSize_{};
    std::array<Voice, kVoiceCount> voices_{};
    std::array<Track, kTrackCount> tracks_{};
    std::array<float, 2049> sine_{};
    std::array<double, 128> frequencies_{};
    uint64_t nextOrder_{}; // Single, serialized control producer.
    uint64_t frame_{};
    uint64_t voiceAge_{};
    uint64_t minNoteOrder_{};
    double sampleRate_{48000.0};
    double beat_{};
    double nextClickBeat_{};
    double clickPhase_{};
    float clickEnvelope_{};
    float attackStep_{};
    float smoothing_{};
    float master_{0.7f};
    float targetMaster_{0.7f};
    float bpm_{145.0f};
    bool playing_{};
    bool metronome_{};
    uint32_t late_{};
    uint32_t clipped_{};

    std::atomic<bool> panic_{false};
    std::atomic<uint32_t> dropped_{0};
    std::atomic<uint64_t> publishedFrame_{0};
    std::atomic<uint64_t> publishedBeat_{0};
    std::atomic<uint32_t> publishedVoices_{0};
    std::atomic<uint32_t> publishedLate_{0};
    std::atomic<uint32_t> publishedClipped_{0};
    std::atomic<float> publishedLeft_{0};
    std::atomic<float> publishedRight_{0};
    std::atomic<float> publishedBpm_{145};
    std::atomic<bool> publishedPlaying_{false};
};
static_assert(std::atomic<uint64_t>::is_always_lock_free);
static_assert(std::atomic<float>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);
} // namespace astra
