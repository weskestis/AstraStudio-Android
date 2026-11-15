#include "TempoMap.h"
#include <algorithm>
#include <cmath>
namespace astra::project {
namespace {
struct Segment {double bpm,slope,end;};
Segment segment(const TempoMap& map,double beat,double fallback)noexcept {
    if(!map.enabled||map.points.empty())return {fallback,0,std::numeric_limits<double>::infinity()};
    auto it=std::upper_bound(map.points.begin(),map.points.end(),beat,[](double b,const TempoPoint& p){return b<p.beat;});
    const auto& p=it==map.points.begin()?map.points.front():*(it-1);
    const double end=it==map.points.end()?std::numeric_limits<double>::infinity():it->beat;
    const double slope=p.ramp&&it!=map.points.end()?(it->bpm-p.bpm)/(it->beat-p.beat):0;
    return {p.bpm+slope*(beat-p.beat),slope,end};
}
double duration(double bpm,double slope,double beats)noexcept {
    return std::abs(slope)<1e-12?60*beats/bpm:60*std::log1p(slope*beats/bpm)/slope;
}
}
bool TempoMap::valid()const noexcept {
    if(points.size()>128||(enabled&&points.empty())||(!points.empty()&&points.front().beat!=0))return false;
    double previous=-1;
    for(const auto& p:points){if(!std::isfinite(p.beat)||!std::isfinite(p.bpm)||p.beat<=previous||p.beat>65536||p.bpm<30||p.bpm>300)return false;previous=p.beat;}
    return true;
}
double TempoMap::bpmAt(double beat,double fallback)const noexcept{return segment(*this,beat,fallback).bpm;}
double TempoMap::seconds(double first,double last,double fallback)const noexcept {
    if(last<first)return -seconds(last,first,fallback);
    double result=0;
    while(first<last){const auto s=segment(*this,first,fallback);const double end=std::min(last,s.end);result+=duration(s.bpm,s.slope,end-first);first=end;}
    return result;
}
double TempoMap::advance(double beat,double elapsed,double fallback)const noexcept {
    while(elapsed>0){const auto s=segment(*this,beat,fallback);const double available=std::isfinite(s.end)?duration(s.bpm,s.slope,s.end-beat):std::numeric_limits<double>::infinity();
        if(elapsed>=available){beat=s.end;elapsed-=available;}
        else {beat+=std::abs(s.slope)<1e-12?elapsed*s.bpm/60:s.bpm*std::expm1(s.slope*elapsed/60)/s.slope;break;}}
    return beat;
}
double TempoClock::tick(const TempoMap* map,double beat,double fallback,double rate)noexcept {
    if(!map||!map->enabled)return beat+fallback/(60*rate);
    if(map!=map_||beat!=expected_||fallback!=fallback_||rate!=rate_||beat>=boundary_){
        const auto s=segment(*map,beat,fallback);map_=map;fallback_=fallback;rate_=rate;bpm_=s.bpm;boundary_=s.end;
        factor_=std::exp(s.slope/(60*rate));scale_=std::abs(s.slope)<1e-12?1/(60*rate):std::expm1(s.slope/(60*rate))/s.slope;
    }
    double next=beat+bpm_*scale_;
    if(next>=boundary_){next=map->advance(beat,1/rate,fallback);reset();return next;}
    bpm_*=factor_;expected_=next;return next;
}
}
