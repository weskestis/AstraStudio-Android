#include "AudioEngine.h"
#include "project/OfflineRender.h"
#include "project/StretchRender.h"
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
    if (!engine || frame < -1 || noteId < 0 || type < 0 || type > 22 || type == 10 || type == 11 || type == 15) return JNI_FALSE;
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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativePreset(JNIEnv* env, jobject, jint preset) {
    if (preset < 0 || preset >= astra::synth::kPresetCount) return nullptr;
    std::array<float, astra::synth::kWireCount> values{};
    astra::synth::preset(preset).encode(values);
    auto result = env->NewFloatArray(astra::synth::kWireCount);
    if (result) env->SetFloatArrayRegion(result, 0, astra::synth::kWireCount, values.data());
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeSetPatch(JNIEnv* env, jobject, jlong handle, jint track, jfloatArray data) {
    if (!data || env->GetArrayLength(data) != astra::synth::kWireCount) return JNI_FALSE;
    std::array<float, astra::synth::kWireCount> values{};
    env->GetFloatArrayRegion(data, 0, astra::synth::kWireCount, values.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    astra::synth::Patch patch;
    if (!patch.decode(values)) return JNI_FALSE;
    std::lock_guard guard(registryMutex);
    auto* engine = findEngine(handle);
    return engine && engine->setPatch(track, patch) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeFxPreset(JNIEnv* env,jobject,jint index) {
    if(index<0||index>=4)return nullptr;
    std::array<float,astra::fx::kWireCount> values{};astra::fx::preset(index).encode(values);
    auto result=env->NewFloatArray(astra::fx::kWireCount);
    if(result)env->SetFloatArrayRegion(result,0,astra::fx::kWireCount,values.data());
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeSetEffects(JNIEnv* env,jobject,jlong handle,jint track,jfloatArray data) {
    if(!data||env->GetArrayLength(data)!=astra::fx::kWireCount)return JNI_FALSE;
    std::array<float,astra::fx::kWireCount> values{};env->GetFloatArrayRegion(data,0,astra::fx::kWireCount,values.data());
    if(env->ExceptionCheck())return JNI_FALSE;
    astra::fx::Config config;if(!config.decode(values))return JNI_FALSE;
    std::lock_guard guard(registryMutex);auto* engine=findEngine(handle);return engine&&engine->setEffects(track,config)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeReadFxStats(JNIEnv* env,jobject,jlong handle,jobject buffer) {
    if(!buffer)return JNI_FALSE;
    auto* address=env->GetDirectBufferAddress(buffer);
    if(!address||env->GetDirectBufferCapacity(buffer)<astra::kFxValues*static_cast<jlong>(sizeof(float)))return JNI_FALSE;
    std::lock_guard guard(registryMutex);auto* engine=findEngine(handle);if(!engine)return JNI_FALSE;
    const auto s=engine->effectsSnapshot();std::array<float,astra::kFxValues> values{};int at=0;
    for(float v:s.sidechain)values[at++]=v;
    for(float v:s.bands)values[at++]=v;
    values[at++]=s.limiterReduction;values[at++]=s.latency;
    for(float v:s.spectrum)values[at++]=v;
    values[at++]=s.sampleRate;values[at++]=static_cast<float>(s.meterTrack);values[at]=static_cast<float>(s.analyzerDrops);
    std::memcpy(address,values.data(),sizeof(values));return JNI_TRUE;
}

namespace {
std::atomic<bool> exportBusy{false},exportCancel{false},assetCancel{false};std::atomic<float> exportProgress{0};
struct Utf {JNIEnv* env; jstring value;const char* text;Utf(JNIEnv* e,jstring v):env(e),value(v),text(v?e->GetStringUTFChars(v,nullptr):nullptr){}~Utf(){if(text)env->ReleaseStringUTFChars(value,text);}};
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeSetProject(JNIEnv* env,jobject,jlong handle,jdoubleArray data){
    if(!data||env->GetArrayLength(data)>astra::kProjectWireLimit)return JNI_FALSE;
    try{std::vector<double> wire(static_cast<std::size_t>(env->GetArrayLength(data)));env->GetDoubleArrayRegion(data,0,static_cast<jsize>(wire.size()),wire.data());if(env->ExceptionCheck())return JNI_FALSE;
        std::lock_guard guard(registryMutex);auto* engine=findEngine(handle);return engine&&engine->setProject(wire)?JNI_TRUE:JNI_FALSE;
    }catch(const std::bad_alloc&){return JNI_FALSE;}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeReadSequence(JNIEnv* env,jobject,jlong handle,jobject buffer){
    if(!buffer)return JNI_FALSE;auto* address=env->GetDirectBufferAddress(buffer);
    if(!address||env->GetDirectBufferCapacity(buffer)<astra::kSequenceValues*static_cast<jlong>(sizeof(float)))return JNI_FALSE;
    std::lock_guard guard(registryMutex);auto* engine=findEngine(handle);if(!engine)return JNI_FALSE;const auto s=engine->sequenceSnapshot();std::array<float,astra::kSequenceValues> values{};
    for(int t=0;t<astra::kTrackCount;++t){values[t]=static_cast<float>(s.active[t]);values[astra::kTrackCount+t]=static_cast<float>(s.queued[t]);values[2*astra::kTrackCount+t]=s.phase[t];}values[3*astra::kTrackCount]=static_cast<float>(s.mode);
    std::memcpy(address,values.data(),sizeof(values));return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeLoadAssets(JNIEnv* env,jobject,jlong handle,jintArray ids,jobjectArray paths){
    if(!ids||!paths||env->GetArrayLength(ids)!=env->GetArrayLength(paths)||env->GetArrayLength(ids)>64)return JNI_FALSE;
    assetCancel=false;
    try{astra::project::Samples old,next;std::array<std::string,64> oldPaths,nextPaths;std::array<jint,64> indices{};
        {std::lock_guard guard(registryMutex);auto* e=findEngine(handle);if(!e)return JNI_FALSE;old=e->samples;oldPaths=e->samplePaths;}
        const int count=env->GetArrayLength(ids);env->GetIntArrayRegion(ids,0,count,indices.data());if(env->ExceptionCheck())return JNI_FALSE;std::size_t used=0;int64_t sourceFrames=0;
        for(int i=0;i<count;++i){const int id=indices[i];if(id<0||id>=64||next[id])return JNI_FALSE;
            auto value=static_cast<jstring>(env->GetObjectArrayElement(paths,i));{Utf path(env,value);if(!path.text)return JNI_FALSE;nextPaths[id]=path.text;
                if(old[id]&&oldPaths[id]==path.text)next[id]=old[id];else next[id]=astra::project::readSample(path.text,astra::project::kPlaybackBudget-used,true,&assetCancel);}
            env->DeleteLocalRef(value);if(!next[id])return JNI_FALSE;used+=next[id]->memoryBytes();sourceFrames+=next[id]->frames();if(used>astra::project::kPlaybackBudget||sourceFrames>astra::project::kProjectSourceFrames)return JNI_FALSE;
        }
        std::lock_guard guard(registryMutex);auto* e=findEngine(handle);if(!e)return JNI_FALSE;e->samples=std::move(next);e->samplePaths=std::move(nextPaths);return JNI_TRUE;
    }catch(const std::bad_alloc&){return JNI_FALSE;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeAssetInfo(JNIEnv* env,jobject,jlong handle,jint id){
    std::shared_ptr<const astra::project::Sample> sample;
    {std::lock_guard guard(registryMutex);auto* e=findEngine(handle);if(!e||id<0||id>=64)return nullptr;sample=e->samples[id];}
    if(!sample)return nullptr;std::array<double,1026> result{};result[0]=sample->rate;result[1]=static_cast<double>(sample->frames());
    for(int i=0;i<1024;++i)result[2+i]=sample->peaks[i];
    auto array=env->NewDoubleArray(1026);if(array)env->SetDoubleArrayRegion(array,0,1026,result.data());return array;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeExport(JNIEnv* env,jobject,jlong handle,jstring path,jdoubleArray data,jfloatArray patchData,jfloatArray effectData,jfloatArray mixData,jint rate,jfloat tail,jboolean floating,jint sourceTrack){
    if(!path||!data||!patchData||!effectData||!mixData||env->GetArrayLength(data)>astra::kProjectWireLimit||
       env->GetArrayLength(patchData)!=astra::kTrackCount*astra::synth::kWireCount||env->GetArrayLength(effectData)!=astra::kTrackCount*astra::fx::kWireCount||env->GetArrayLength(mixData)!=astra::project::kMixValues)return 1;
    bool expected=false;if(!exportBusy.compare_exchange_strong(expected,true))return 1;
    struct Done{~Done(){exportBusy.store(false);}}done;exportCancel=false;exportProgress=0;
    try{astra::project::Samples samples;{std::lock_guard guard(registryMutex);auto* e=findEngine(handle);if(!e)return 1;samples=e->samples;}
        Utf output(env,path);if(!output.text)return 2;
        std::vector<double> wire(static_cast<std::size_t>(env->GetArrayLength(data)));env->GetDoubleArrayRegion(data,0,static_cast<jsize>(wire.size()),wire.data());
        std::array<astra::synth::Patch,astra::kTrackCount> patches;std::array<astra::fx::Config,astra::kTrackCount> effects;std::array<float,astra::project::kMixValues> mix;
        env->GetFloatArrayRegion(mixData,0,astra::project::kMixValues,mix.data());
        for(int i=0;i<astra::kTrackCount;++i){std::array<float,astra::synth::kWireCount> p;env->GetFloatArrayRegion(patchData,i*astra::synth::kWireCount,astra::synth::kWireCount,p.data());if(!patches[i].decode(p))return 1;
            std::array<float,astra::fx::kWireCount> f;env->GetFloatArrayRegion(effectData,i*astra::fx::kWireCount,astra::fx::kWireCount,f.data());if(!effects[i].decode(f))return 1;}
        if(env->ExceptionCheck())return 1;
        return astra::project::renderWave(output.text,wire,samples,patches,effects,mix,rate,tail,exportCancel,exportProgress,floating,sourceTrack);
    }catch(const std::bad_alloc&){return 1;}
}
extern "C" JNIEXPORT void JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeCancelExport(JNIEnv*,jobject){exportCancel=true;assetCancel=true;}
extern "C" JNIEXPORT jfloat JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeExportProgress(JNIEnv*,jobject){return exportProgress.load();}

extern "C" JNIEXPORT jint JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeStretch(JNIEnv* env,jobject,jlong handle,jstring path,jint id,jlong first,jlong last,jlong frames,jdouble semitones){
    if(!path||id<0||id>=64)return 1;
    bool expected=false;if(!exportBusy.compare_exchange_strong(expected,true))return 1;
    struct Done{~Done(){exportBusy.store(false);}}done;exportCancel=false;exportProgress=0;
    try{std::shared_ptr<const astra::project::Sample> sample;
        {std::lock_guard guard(registryMutex);auto* e=findEngine(handle);if(!e)return 1;sample=e->samples[id];}
        if(!sample||frames<=0||frames>astra::project::kMaxSourceFrames)return 1;
        sample=astra::project::offlineSample(sample);if(!sample)return 2;
        Utf output(env,path);if(!output.text)return 2;
        return astra::project::stretchWave(output.text,*sample,first,last,frames,semitones,exportCancel,exportProgress);
    }catch(const std::exception&){return 1;}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_weskestis_astrastudio_audio_AudioBridge_nativeStorageStats(JNIEnv* env,jobject,jlong handle){
    std::array<jlong,6> values{};
    {std::lock_guard guard(registryMutex);auto* e=findEngine(handle);if(!e)return nullptr;
        for(const auto& s:e->samples)if(s){values[0]+=s->memoryBytes();values[1]+=s->frames()*8;values[2]+=s->stream?1:0;values[3]+=s->misses();values[4]+=s->reads();values[5]+=s->failed()?1:0;}}
    auto result=env->NewLongArray(6);if(result)env->SetLongArrayRegion(result,0,6,values.data());return result;
}
