#pragma once
#include "FxModules.h"
namespace astra::fx {
class Rack {
public:
    static constexpr int latency=15;
    bool prepare(double rate) noexcept;
    void configure(const Config& config) noexcept;
    void tempo(float bpm) noexcept {if(bpm_!=bpm){bpm_=bpm;dirty_=true;}}
    void reset() noexcept;
    Stereo tick(Stereo input,Stereo externalDetector) noexcept;
    int detectorTrack() const noexcept{return static_cast<int>(config_[ScSource]);}
    float reduction() const noexcept{return unitMix_[3]>.001f?sidechain_.reduction():0;}
    std::array<float,3> bandMeters() const noexcept{return unitMix_[1]>.001f?multiband_.meters():std::array<float,3>{};}
private:
    void update() noexcept;void resetUnit(int id) noexcept;Stereo unit(int id,Stereo x,Stereo detector) noexcept;
    Config config_;Values values_{},target_{};std::array<float,kUnits> unitMix_{};
    Equalizer eq_;Multiband multiband_;Saturator saturator_;Sidechain sidechain_;PingPong delay_;Reverb reverb_;Chorus chorus_;ModulationFx modulation_;
    FixedDelay<latency> dry_,parallelPad_,detectorDelay_;
    Stereo last_{},tail_{};float rate_{48000},bpm_{145},smooth_{.004f},controlSmooth_{.1f},outputGain_{1};int countdown_{},fade_{};bool dirty_{true},bypassed_{true};
};
} // namespace astra::fx
