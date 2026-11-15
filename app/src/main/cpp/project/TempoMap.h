#pragma once
#include <vector>
#include <limits>
namespace astra::project {
struct TempoPoint {double beat{},bpm{145};bool ramp{};bool operator==(const TempoPoint&)const=default;};
// Ramps are linear in musical beats. Integrating 60/BPM gives seconds.
struct TempoMap {
    bool enabled{};std::vector<TempoPoint> points;
    double bpmAt(double beat,double fallback)const noexcept;
    double seconds(double first,double last,double fallback)const noexcept;
    double advance(double beat,double seconds,double fallback)const noexcept;
    bool valid()const noexcept;
};
// One exponential is prepared per segment, not per audio sample.
class TempoClock {
public:
    void reset()noexcept{expected_=std::numeric_limits<double>::quiet_NaN();}
    double tick(const TempoMap* map,double beat,double fallback,double rate)noexcept;
private:
    const TempoMap* map_{};double expected_{std::numeric_limits<double>::quiet_NaN()},fallback_{},rate_{},bpm_{},boundary_{},factor_{1},scale_{};
};
}
