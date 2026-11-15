#pragma once
#include "SynthPatch.h"
#include "WavetableBank.h"
#include "Modulators.h"
#include "Filter.h"
#include "FMOperator.h"
#include <array>
#include <cstdint>

namespace astra::synth {
class SynthVoice {
public:
    void start(int note, float velocity, uint32_t seed, const Patch& patch, double sampleRate,
               const std::array<double, 4>& freePhases, float bpm) noexcept;
    void release() noexcept;
    void silence() noexcept { lastSample_ = tail_ = 0; fade_ = 0; for (auto& e : envelopes_) e = Envelope{}; }
    float render(const Patch& patch, const WavetableBank& bank,
                 const std::array<double, 4>& freePhases, float bpm) noexcept;
    bool active() const noexcept { return envelopes_[0].active() || fade_ > 0; }
    bool releasing() const noexcept { return envelopes_[0].releasing(); }
    float amplitude() const noexcept { return envelopes_[0].value(); }
private:
    enum Control : int { IncA, IncB, IncSub, PositionA, PositionB, Amount, Index, LevelA, LevelB,
        Sub, Noise, Gain, MipA, MipB, WarpA, WarpB, Amplitude, Feedback,
        OpInc1, OpInc2, OpInc3, OpInc4, OpLevel1, OpLevel2, OpLevel3, OpLevel4, Count };
    void update(const Patch& patch, const std::array<double, 4>& freePhases, float bpm, bool initial) noexcept;
    static double warped(double phase, int mode, float amount) noexcept;
    std::array<Envelope, 7> envelopes_{};
    std::array<float, ParameterCount> smoothed_{};
    std::array<float, Count> controls_{}, steps_{};
    std::array<double, 4> lfoPhase_{}, lfoIncrement_{};
    std::array<Filter, 2> filters_{};
    FMOperatorSynth fm_;
    std::array<float, 32> fir_{};
    std::array<int, 8> modes_{};
    double phaseA_{}, phaseB_{}, phaseSub_{};
    double sampleRate_{48000};
    float baseFrequency_{}, velocity_{}, key_{}, smoothCoefficient_{}, lastSample_{}, tail_{};
    float dcInput_{}, dcOutput_{}, dcPole_{};
    uint32_t random_{1};
    int controlCountdown_{}, firWrite_{}, fade_{};
    bool parallel_{};
};
} // namespace astra::synth
