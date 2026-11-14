#include "AudioEngine.h"
#include <jni.h>
#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>

namespace {
// This lock only protects JNI/control calls. Never acquired by onAudioReady.
std::mutex registryMutex;
std::unordered_map<jlong, std::unique_ptr<astra::AudioEngine>> engines;
jlong nextHandle = 1;
constexpr jint kBadHandle = -10000;
constexpr jint kAllocationFailure = -10001;
constexpr jsize kStatsCount = 20;

astra::AudioEngine* findEngine(jlong handle) {
    const auto found = engines.find(handle);
    return found == engines.end() ? nullptr : found->second.get();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeCreate(JNIEnv*, jobject) {
    std::lock_guard guard(registryMutex);
    if (nextHandle == std::numeric_limits<jlong>::max()) return 0;
    try {
        const jlong handle = nextHandle++;
        engines.emplace(handle, std::make_unique<astra::AudioEngine>());
        return handle;
    } catch (const std::bad_alloc&) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    std::lock_guard guard(registryMutex);
    engines.erase(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeStart(JNIEnv*, jobject, jlong handle, jint bursts) {
    std::lock_guard guard(registryMutex);
    auto* engine = findEngine(handle);
    if (!engine) return kBadHandle;
    try { return engine->start(bursts); }
    catch (const std::bad_alloc&) { engine->stop(); return kAllocationFailure; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeStop(JNIEnv*, jobject, jlong handle) {
    std::lock_guard guard(registryMutex);
    if (auto* engine = findEngine(handle)) engine->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeSend(
        JNIEnv*, jobject, jlong handle, jint type, jint track, jint note, jint noteId, jfloat value, jlong frame) {
    std::lock_guard guard(registryMutex);
    auto* engine = findEngine(handle);
    if (!engine || frame < -1 || noteId < 0) return JNI_FALSE;
    const astra::Command command{static_cast<astra::CommandType>(type), track, note,
        static_cast<uint32_t>(noteId), value, frame == -1 ? astra::kImmediate : static_cast<uint64_t>(frame), 0};
    return engine->enqueue(command) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativePanic(JNIEnv*, jobject, jlong handle) {
    std::lock_guard guard(registryMutex);
    if (auto* engine = findEngine(handle)) engine->panic();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeSetBuffer(JNIEnv*, jobject, jlong handle, jint bursts) {
    std::lock_guard guard(registryMutex);
    if (auto* engine = findEngine(handle)) return engine->setBufferBursts(bursts);
    return kBadHandle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeReadStats(JNIEnv* env, jobject, jlong handle, jobject destination) {
    if (!destination) return JNI_FALSE;
    auto* address = env->GetDirectBufferAddress(destination);
    const auto capacity = env->GetDirectBufferCapacity(destination);
    if (!address || capacity < static_cast<jlong>(kStatsCount * sizeof(double))) return JNI_FALSE;
    std::lock_guard guard(registryMutex);
    auto* engine = findEngine(handle);
    if (!engine) return JNI_FALSE;
    const auto s = engine->snapshot();
    const std::array<double, kStatsCount> values{
        s.running ? 1.0 : 0.0, static_cast<double>(s.sampleRate), static_cast<double>(s.framesPerBurst),
        static_cast<double>(s.bufferFrames), s.cpuLoad, static_cast<double>(s.xruns),
        static_cast<double>(s.core.frame), static_cast<double>(s.core.activeVoices),
        s.core.peakLeft, s.core.peakRight, static_cast<double>(s.core.droppedCommands),
        static_cast<double>(s.core.lateCommands), static_cast<double>(s.core.clippedSamples),
        static_cast<double>(s.error), s.exclusive ? 1.0 : 0.0, s.core.bpm,
        s.core.playing ? 1.0 : 0.0, static_cast<double>(s.core.beatMicro) / 1000000.0,
        astra::kTrackCount, astra::kVoiceCount
    };
    // Copy rather than casting the pointer: caller might supply a sliced buffer
    // whose address is not aligned for double. No native pointer escapes to UI.
    std::memcpy(address, values.data(), sizeof(values));
    return JNI_TRUE;
}
