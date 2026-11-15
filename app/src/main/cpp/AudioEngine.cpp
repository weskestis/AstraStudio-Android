#include "AudioEngine.h"
#include <algorithm>
#include <chrono>

namespace astra {
AudioEngine::~AudioEngine() { stop(); }

int32_t AudioEngine::start(int32_t bursts) {
    if (bursts < 2 || bursts > 8) return static_cast<int32_t>(oboe::Result::ErrorOutOfRange);
    if (stream_ && callback_->error.load(std::memory_order_acquire) == 0) return 0;
    stop();
    callback_ = std::make_shared<Callback>();
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setAudioApi(oboe::AudioApi::AAudio);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(2);
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
    builder.setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(callback_));
    builder.setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(callback_));
    // Do not force 44.1/48 kHz: accept the route's native sample rate.
    auto result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        stream_.reset();
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }
    if (result != oboe::Result::OK) {
        lastError_ = static_cast<int32_t>(result);
        stream_.reset();
        callback_.reset();
        return lastError_;
    }
    if (stream_->getFormat() != oboe::AudioFormat::Float || stream_->getChannelCount() != 2 ||
        !callback_->core.prepare(stream_->getSampleRate())) {
        stop();
        lastError_ = static_cast<int32_t>(oboe::Result::ErrorInvalidFormat);
        return lastError_;
    }
    callback_->sampleRate = stream_->getSampleRate();
    // Some routes fix their buffer size. A refused tuning request is not fatal.
    setBufferBursts(bursts);
    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        stop();
        lastError_ = static_cast<int32_t>(result);
        return lastError_;
    }
    lastError_ = 0;
    return 0;
}

void AudioEngine::stop() noexcept {
    if (callback_) callback_->halt.store(true, std::memory_order_release);
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
    // Oboe retains shared callback ownership until its final callback finishes.
    // The callback has no pointer to this AudioEngine or to Kotlin objects.
    callback_.reset();
}

bool AudioEngine::enqueue(Command command) noexcept {
    if (!stream_ || !callback_ || callback_->error.load(std::memory_order_acquire) != 0) return false;
    return callback_->core.enqueue(command);
}

bool AudioEngine::setPatch(int track, const synth::Patch& patch) noexcept {
    return stream_ && callback_ && callback_->error.load(std::memory_order_acquire) == 0 && callback_->core.setPatch(track, patch);
}

bool AudioEngine::setEffects(int track,const fx::Config& config) noexcept {
    return stream_ && callback_ && callback_->error.load(std::memory_order_acquire)==0 && callback_->core.setEffects(track,config);
}
bool AudioEngine::setProject(std::span<const double> data) noexcept {return stream_&&callback_&&callback_->error.load(std::memory_order_acquire)==0&&callback_->core.setProject(data,samples);}
project::Snapshot AudioEngine::sequenceSnapshot()const noexcept{return callback_?callback_->core.sequenceSnapshot():project::Snapshot{};}
FxSnapshot AudioEngine::effectsSnapshot() noexcept { return callback_?callback_->core.effectsSnapshot():FxSnapshot{}; }
void AudioEngine::panic() noexcept {
    if (callback_) callback_->core.panic();
}

int32_t AudioEngine::setBufferBursts(int32_t bursts) noexcept {
    if (!stream_) return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
    if (bursts < 2 || bursts > 8) return static_cast<int32_t>(oboe::Result::ErrorOutOfRange);
    if (callback_->error.load(std::memory_order_acquire) != 0) return static_cast<int32_t>(oboe::Result::ErrorDisconnected);
    const auto wanted = std::min(stream_->getFramesPerBurst() * bursts, stream_->getBufferCapacityInFrames());
    const auto result = stream_->setBufferSizeInFrames(wanted);
    return result ? result.value() : static_cast<int32_t>(result.error());
}

EngineSnapshot AudioEngine::snapshot() const noexcept {
    EngineSnapshot s;
    s.error = lastError_;
    if (!stream_ || !callback_) return s;
    s.core = callback_->core.snapshot();
    s.error = callback_->error.load(std::memory_order_acquire);
    s.running = s.error == 0 && !callback_->halt.load(std::memory_order_acquire);
    s.sampleRate = stream_->getSampleRate();
    s.framesPerBurst = stream_->getFramesPerBurst();
    s.bufferFrames = stream_->getBufferSizeInFrames();
    s.exclusive = stream_->getSharingMode() == oboe::SharingMode::Exclusive;
    s.cpuLoad = callback_->load.load(std::memory_order_relaxed);
    if (s.error == 0) {
        const auto xruns = stream_->getXRunCount();
        if (xruns) s.xruns = xruns.value();
    }
    return s;
}

oboe::DataCallbackResult AudioEngine::Callback::onAudioReady(oboe::AudioStream*, void* audioData, int32_t frames) {
    auto* output = static_cast<float*>(audioData);
    if (frames <= 0 || output == nullptr) return oboe::DataCallbackResult::Stop;
    if (halt.load(std::memory_order_acquire)) {
        std::fill_n(output, static_cast<std::size_t>(frames) * 2, 0.0f);
        return oboe::DataCallbackResult::Stop;
    }
    const auto before = std::chrono::steady_clock::now();
    core.render(output, frames);
    const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - before).count();
    const auto ratio = static_cast<float>(elapsed * static_cast<double>(sampleRate) / frames);
    const float oldLoad = load.load(std::memory_order_relaxed);
    load.store(oldLoad + 0.1f * (ratio - oldLoad), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

bool AudioEngine::Callback::onError(oboe::AudioStream*, oboe::Result result) {
    error.store(static_cast<int32_t>(result), std::memory_order_release);
    // Explicitly take lifecycle ownership. AudioService polls on its control
    // executor and invokes stop/start; onDestroy always closes as a fallback.
    // Returning true prevents Oboe racing our control thread to close the stream.
    return true;
}
} // namespace astra
