#pragma once
#include <cstdio>
#include <span>
#include <cstdint>
namespace astra::project {
// Float preserves the rendered signal without quantization or added dither.
// PCM24 uses deterministic TPDF dither once, at the final delivery conversion.
class WaveWriter {
public:
    WaveWriter(const char* path,int rate,uint64_t frames,bool floating)noexcept;
    ~WaveWriter(){if(file_)std::fclose(file_);}
    bool good()const noexcept{return ok_;}
    bool write(std::span<const float> stereo)noexcept;
    bool finish()noexcept;
private:
    std::FILE* file_{};bool float_{},ok_{};uint64_t remaining_{};uint32_t rng_{0x41535452};
};
}
