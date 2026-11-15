#pragma once
#include "Project.h"
#include "dsp/SynthPatch.h"
#include "fx/FxConfig.h"
namespace astra::project {
constexpr int kMixValues=4+16*6;
// Control/worker thread only. Uses the same RenderCore as Oboe and writes 24-bit
// stereo PCM. Return 0 success, 1 invalid configuration, 2 I/O, 3 cancellation.
int renderWave(const char* path,std::span<const double> wire,const Samples& samples,
    const std::array<synth::Patch,16>& patches,const std::array<fx::Config,16>& effects,
    const std::array<float,kMixValues>& mix,int rate,float tail,std::atomic<bool>& cancel,std::atomic<float>& progress);
}
