#include "FxConfig.h"
#include <algorithm>
#include <cmath>
namespace astra::fx {
Config::Config() noexcept { for(int i=0;i<ParameterCount;++i) values[i]=specs[i].initial; }
bool Config::valid() const noexcept {
    for(int i=0;i<ParameterCount;++i){const float v=values[i];const auto& s=specs[i];
        if(!std::isfinite(v)||v<s.low||v>s.high||(s.integer&&v!=std::floor(v)))return false;}
    unsigned seen=0;
    for(int i:order){if(i<0||i>=kUnits||(seen&(1U<<i)))return false;seen|=1U<<i;}
    for(int i:lane)if(i<0||i>1)return false;
    for(const auto& m:macros)if(m.target<0||m.target>16||!std::isfinite(m.depth)||!std::isfinite(m.value)||std::abs(m.depth)>1||m.value<0||m.value>1)return false;
    for(int i=0;i<3;++i)if(values[Ott1Lower+i*3]>=values[Ott1Upper+i*3])return false;
    return true;
}
bool Config::decode(std::span<const float> wire) noexcept {
    if(wire.size()!=kWireCount)return false;
    for(float f:wire)if(!std::isfinite(f))return false;
    Config next;int at=0;
    for(float& f:next.values)f=wire[at++];
    auto number=[&](int maximum,int& output){const float f=wire[at++];if(f<0||f>maximum||f!=std::floor(f))return false;output=static_cast<int>(f);return true;};
    for(auto& i:next.order)if(!number(7,i))return false;
    for(auto& i:next.lane)if(!number(1,i))return false;
    for(auto& m:next.macros){if(!number(16,m.target))return false;m.depth=wire[at++];m.value=wire[at++];}
    if(!next.valid())return false;
    *this=next;return true;
}
void Config::encode(std::span<float,kWireCount> wire) const noexcept {
    int at=0;for(float f:values)wire[at++]=f;
    for(int i:order)wire[at++]=static_cast<float>(i);
    for(int i:lane)wire[at++]=static_cast<float>(i);
    for(const auto& m:macros){wire[at++]=static_cast<float>(m.target);wire[at++]=m.depth;wire[at++]=m.value;}
}
std::array<float,ParameterCount> Config::effective() const noexcept {
    auto result=values;
    for(const auto& m:macros)if(m.target){const int id=macroTargets[m.target-1];result[id]+=m.value*m.depth*(specs[id].high-specs[id].low);}
    for(int i=0;i<ParameterCount;++i)result[i]=std::clamp(result[i],specs[i].low,specs[i].high);
    return result;
}
bool Config::structureEquals(const Config& b) const noexcept {
    if(order!=b.order||lane!=b.lane)return false;
    for(int id:{RackMode,EqMs,ShaperMode,ScSource,ModMode})if(values[id]!=b.values[id])return false;
    for(int i=0;i<8;++i)for(int id:{Eq1Type+i*6,Eq1Channel+i*6})if(values[id]!=b.values[id])return false;
    return true;
}
Config preset(int index) noexcept {
    Config c;
    if(index==1){
        c[EqOn]=1;c[Eq1Type]=4;c[Eq1Frequency]=30;c[Eq4Gain]=-2;c[Eq6Gain]=2;
        c[OttOn]=1;c[OttDepth]=.65f;c[ShaperOn]=1;c[ShaperPre]=12;c[ShaperPost]=-6;c[ShaperMix]=.65f;
        c.macros[0]={1,.25f,0};c.macros[1]={5,.5f,0};c.macros[2]={3,.4f,0};c.macros[3]={16,-.3f,0};
    }else if(index==2){
        c[EqOn]=1;c[Eq1Type]=4;c[Eq1Frequency]=70;c[ChorusOn]=1;c[ChorusDepth]=.6f;
        c[DelayOn]=1;c[DelayMix]=.2f;c[ReverbOn]=1;c[ReverbMix]=.18f;c[ReverbDecay]=4;
        c.macros[0]={2,.4f,0};c.macros[1]={3,.4f,0};c.macros[2]={4,.4f,0};c.macros[3]={16,-.3f,0};
    }else if(index==3){
        c[ScOn]=1;c[ScSource]=0;c[ScThreshold]=-34;c[ScRatio]=8;c[ScAttack]=1;c[ScRelease]=90;
        c.macros[0]={6,-.25f,0};c.macros[1]={2,.35f,0};c.macros[2]={3,.3f,0};c.macros[3]={16,-.3f,0};
    }
    return c;
}
} // namespace astra::fx
