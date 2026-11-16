#pragma once
#include <cstddef>
#include <span>
#include <vector>
namespace astra::synth {
// Immutable band-limited cycles, built on the I/O thread. Every source cycle is retained.
class ImportedWavetable {
public:
    ImportedWavetable(std::span<const float> stereo,int cycle);
    float read(float position,double phase,float mip)const noexcept;
    float mipFor(float frequency,float sampleRate)const noexcept;
    int cycle()const noexcept{return cycle_;}
    int frames()const noexcept{return frames_;}
    std::size_t memoryBytes()const noexcept{return data_.size()*sizeof(float)+offsets_.size()*sizeof(std::size_t);}
    static std::size_t requiredBytes(int cycle,int frames)noexcept;
private:
    int cycle_{},frames_{},levels_{};std::size_t stride_{};
    std::vector<float> data_;std::vector<std::size_t> offsets_;
    float lookup(int frame,int level,double phase)const noexcept;
};
}
