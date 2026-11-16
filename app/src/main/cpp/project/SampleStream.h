#pragma once
#include "fx/FxPrimitives.h"
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace astra::project {
constexpr std::size_t kSampleBudget=512ULL*1024*1024; // Legacy resident-reader test/offline budget.
constexpr std::size_t kPlaybackBudget=128ULL*1024*1024;
constexpr std::size_t kResidentThreshold=1024*1024;
constexpr int64_t kMaxSourceFrames=268435456;
constexpr int64_t kProjectSourceFrames=1073741824;
struct SampleCursor { int64_t page{-1};const void* source{};int slot{-1};unsigned retry{}; };
class SampleStream;
struct Sample {
    int rate{};std::vector<float> stereo;
    std::shared_ptr<SampleStream> stream;
    std::array<float,1024> peaks{};
    int64_t sourceFrames{};
    int64_t frames()const noexcept{return stream?sourceFrames:static_cast<int64_t>(stereo.size()/2);}
    std::size_t memoryBytes()const noexcept;
    bool read(int64_t first,float* stereoOut,int count,SampleCursor&)const noexcept;
    void prefetch(int64_t frame)const noexcept;
    bool failed()const noexcept;
    uint64_t misses()const noexcept;
    uint64_t reads()const noexcept;
};
// File parsing, allocation, priming and registration run only on the I/O thread.
std::shared_ptr<const Sample> readSample(const char*,std::size_t,bool streaming=false,const std::atomic<bool>* cancel=nullptr);
// Independent bounded synchronous reader: export I/O never competes for live cache pages.
std::shared_ptr<const Sample> offlineSample(const std::shared_ptr<const Sample>&);
} // namespace astra::project
