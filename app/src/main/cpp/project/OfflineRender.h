#pragma once
#include "Project.h"
#include "dsp/SynthPatch.h"
#include "fx/FxConfig.h"
namespace astra::project {
constexpr int kMixValues=4+16*6;
inline constexpr std::array<int,6> kExportRates{44100,48000,88200,96000,176400,192000};
// Control/worker thread only. Uses the same RenderCore as Oboe and writes float32 or 24-bit
// stereo PCM. Return 0 success, 1 invalid configuration, 2 I/O, 3 cancellation.
int renderWave(const char* path,std::span<const double> wire,const Samples& samples,
    const std::array<synth::Patch,16>& patches,const std::array<fx::Config,16>& effects,
    const std::array<float,kMixValues>& mix,int rate,float tail,std::atomic<bool>& cancel,std::atomic<float>& progress,bool floating=false,int sourceTrack=-1);
}
