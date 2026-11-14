#pragma once
#include "core/RenderCore.h"
#include <oboe/Oboe.h>
#include <memory>

namespace astra {
struct EngineSnapshot {
    CoreSnapshot core{};
    bool running{};
    int32_t sampleRate{};
    int32_t framesPerBurst{};
    int32_t bufferFrames{};
    int32_t xruns{-1};
    int32_t error{};
    bool exclusive{};
    float cpuLoad{};
};

// All public control methods are serialized by the JNI registry mutex.
// The realtime callback never calls them and never touches that mutex.
class AudioEngine final {
public:
    AudioEngine() = default;
    ~AudioEngine();
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;
    int32_t start(int32_t bursts);
    void stop() noexcept;
    bool enqueue(Command command) noexcept;
    void panic() noexcept;
    int32_t setBufferBursts(int32_t bursts) noexcept;
    EngineSnapshot snapshot() const noexcept;
private:
    class Callback final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
    public:
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void*, int32_t) override;
        bool onError(oboe::AudioStream*, oboe::Result error) override;
        RenderCore core;
        std::atomic<bool> halt{false};
        std::atomic<int32_t> error{0};
        std::atomic<float> load{0};
        int32_t sampleRate{48000}; // Immutable once requestStart is called.
    };
    std::shared_ptr<Callback> callback_;
    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t lastError_{};
};
} // namespace astra
