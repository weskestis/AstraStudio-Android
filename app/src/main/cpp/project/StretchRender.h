#pragma once
#include "Project.h"
namespace astra::project {
// Worker-only. Never called by the realtime renderer. Returns 0 / invalid 1 /
// file error 2 / cancelled 3, and writes IEEE float stereo at the source rate.
int stretchWave(const char* path,const Sample& sample,int64_t first,int64_t last,int64_t outputFrames,double semitones,
                std::atomic<bool>& cancel,std::atomic<float>& progress);
}
