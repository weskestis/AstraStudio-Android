#include "core/RenderCore.h"
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <vector>
#include <limits>
extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations;
using namespace astra;
using namespace astra::fx;
namespace {
int checks=0;
void check(bool ok,const char* label){++checks;if(!ok){std::fprintf(stderr,"FAIL: %s\n",label);std::exit(1);}}
void close(float a,float b,float tolerance,const char* label){if(std::abs(a-b)>tolerance)std::fprintf(stderr,"values %.9g %.9g\n",a,b);
        check(std::abs(a-b)<=tolerance,label);}
float tone(int i,float hz,float rate=48000){return std::sin(2*std::numbers::pi_v<float>*hz*i/rate);}
void render(RenderCore& c,float* out,int n){inRender=true;c.render(out,n);inRender=false;}
double energy(const std::vector<float>& x,int start=0){double e=0;for(std::size_t i=start;i<x.size();++i)e+=x[i]*x[i];return e;}
Command note(int track,uint32_t id,int pitch=57,uint64_t frame=kImmediate){return {CommandType::NoteOn,track,pitch,id,.8f,frame};}
}
int main(){
    {
        Config c;std::array<float,kWireCount> wire{};c.encode(wire);Config decoded;
        check(decoded.decode(wire)&&decoded.valid(),"wire roundtrip");
        auto bad=wire;bad[ParameterCount]=1;
        check(!decoded.decode(bad),"duplicate order rejected");
        bad=wire;bad[ScSource]=.5f;
        check(!decoded.decode(bad),"fractional routing rejected");
        bad=wire;bad[Ott1Lower]=bad[Ott1Upper];
        check(!decoded.decode(bad),"crossed thresholds rejected");
        bad=wire;bad[ShaperPre]=std::numeric_limits<float>::quiet_NaN();
        check(!decoded.decode(bad),"NaN rejected");
        check(!decoded.decode(std::span<const float>(wire.data(),wire.size()-1)),"short wire rejected");
        c.macros[0]={1,1,1};close(c.effective()[ShaperPre],specs[ShaperPre].high,0,"macro clamps");
        for(int i=0;i<4;++i)check(preset(i).valid(),"valid factory rack");
    }
    {
        Delay d;check(d.prepare(2884),"delay buffer prepare");d.push(.5f);close(d.read(1),.5f,0,"integer delay does not read unfilled neighbor");
        d.reset();for(int i=0;i<3184;++i)d.push(static_cast<float>(i));
        close(d.read(std::nextafter(300.f,301.f)),2884.f,.001f,"fractional wrap stays inside buffer");
        close(d.read(300.5f),2883.5f,.001f,"fractional wrap interpolation");
        d.reset();close(d.read(1),0,0,"reset hides stale delay memory");
    }
    for(float rate:{8000.f,44100.f,48000.f,96000.f,192000.f,384000.f}){
        LookaheadLimiter limiter;
        check(limiter.prepare(rate),"limiter rate");
        check(limiter.latency()==static_cast<int>(std::ceil(rate*.005)),"5ms latency");
        for(int i=0;i<limiter.latency()+2;++i){const auto y=limiter.tick(i==0?Stereo{.4f,.2f}:Stereo{});
            close(y.l,i==limiter.latency()?.4f:0,1e-7f,"limiter impulse exact delay");close(y.r,y.l*.5f,1e-7f,"limiter stereo link");}
        limiter.reset();
        for(int i=0;i<30000;++i){const float x=(i%997==0?64.0f:4*tone(i,350,rate));const auto y=limiter.tick({x,x*.25f});
            check(std::isfinite(y.l)&&peak(y)<=LookaheadLimiter::ceiling,"limiter never overshoots");close(y.r,y.l*.25f,1e-6f,"limiter preserves stereo ratio");}
        for(int i=0;i<static_cast<int>(rate);++i)limiter.tick({});
        check(limiter.reduction()<.2f,"limiter release recovers");
    }
    {
        Rack rack;
        check(rack.prepare(48000),"rack prepare");
        for(int mode=0;mode<2;++mode)for(int lane=0;lane<2;++lane){Config c;c[RackMode]=static_cast<float>(mode);c.lane[2]=lane;rack.configure(c);
            for(int i=0;i<4096;++i)rack.tick({},{});
            rack.reset();
            for(int i=0;i<100;++i){const auto y=rack.tick(i==0?Stereo{.2f,-.1f}:Stereo{},{});
                close(y.l,i==15?.2f:0,1e-7f,"serial and parallel bypass align at 15 frames");close(y.r,-y.l*.5f,1e-7f,"bypass stereo");}}
        Saturator s;s.prepare(48000);Config c;c[ShaperMix]=0;s.set(c.values,48000);
        for(int i=0;i<100;++i){const auto y=s.tick(i==0?Stereo{.2f,.2f}:Stereo{},1);close(y.l,i==15?.2f:0,1e-7f,"enabled saturator dry alignment");}
        for(int mode=0;mode<4;++mode){s.reset();c[ShaperMode]=static_cast<float>(mode);c[ShaperMix]=1;c[ShaperPre]=18;s.set(c.values,48000);double difference=0;
            for(int i=0;i<48000;++i){float x=.5f*tone(i,300);const auto y=s.tick({x,x},1);
        check(std::isfinite(y.l)&&peak(y)<2.5f,"oversampled shaper bounded");difference+=std::abs(y.l-x);}
            check(difference>100,"shaper changes signal");}
    }
    {
        Config c;Equalizer eq;c[Eq1Frequency]=1000;c[Eq1Gain]=6;c[Eq1Q]=1;eq.set(c.values,48000);
        double input=0,output=0;for(int i=0;i<48000;++i){const float x=.1f*tone(i,1000);const auto y=eq.tick({x,x});if(i>24000){input+=x*x;output+=y.l*y.l;}}
        close(static_cast<float>(10*std::log10(output/input)),6,.02f,"EQ center gain");
        c[EqMs]=1;c[Eq1Channel]=2;eq.reset();eq.set(c.values,48000);input=output=0;
        for(int i=0;i<48000;++i){float x=.1f*tone(i,1000);auto y=eq.tick({x,x});if(i>24000){input+=x*x;output+=y.l*y.l;}}
        close(static_cast<float>(output/input),1,.001f,"side EQ leaves mono mid alone");
        for(int type=0;type<6;++type){c[Eq1Type]=static_cast<float>(type);c[EqMs]=0;eq.set(c.values,48000);for(int i=0;i<12000;++i)check(std::isfinite(eq.tick({tone(i,2000),.2f}).l),"all EQ types stable");}
    }
    {
        Config c;c[OttUp]=c[OttDown]=0;c[OttDepth]=1;
        for(float hz:{60.f,180.f,1000.f,4000.f,12000.f}){Multiband m;m.set(c.values,48000);double input=0,output=0;
            for(int i=0;i<48000;++i){float x=.1f*tone(i,hz);auto y=m.tick({x,x});if(i>24000){input+=x*x;output+=y.l*y.l;}}
            close(static_cast<float>(10*std::log10(output/input)),0,.03f,"neutral LR4 sum flat magnitude");}
        c[OttUp]=c[OttDown]=1;c[Ott1Lower]=c[Ott2Lower]=c[Ott3Lower]=-35;c[Ott1Upper]=c[Ott2Upper]=c[Ott3Upper]=-20;
        Multiband m;m.set(c.values,48000);for(int i=0;i<48000;++i)m.tick({.005f*tone(i,1000),.005f*tone(i,1000)});
        check(m.meters()[1]>3,"OTT upward compression");
        for(int i=0;i<48000;++i)m.tick({.8f*tone(i,1000),.8f*tone(i,1000)});
        check(m.meters()[1]<-5,"OTT downward compression");
        m.reset();for(int i=0;i<1000;++i)check(peak(m.tick({}))==0,"OTT exact silent floor");
        close(Sidechain::curve(-10,-20,4,0),7.5f,1e-6f,"compressor ratio");
        close(Sidechain::curve(-23,-20,4,6),0,1e-6f,"soft knee lower edge");
        close(Sidechain::curve(-17,-20,4,6),2.25f,1e-6f,"soft knee upper edge");
        Sidechain sc;c[ScThreshold]=-30;c[ScRatio]=8;c[ScAttack]=1;c[ScRelease]=50;sc.set(c.values,48000);
        for(int i=0;i<24000;++i){auto y=sc.tick({.2f,.1f},{tone(i,100),tone(i,100)});close(y.r,y.l*.5f,1e-7f,"sidechain stereo linked");}
        check(sc.reduction()>20,"external detector ducks signal");
        for(int i=0;i<48000;++i)sc.tick({.2f,.1f},{});
        check(sc.reduction()<.05f,"compressor release");
    }
    {
        Config c;c[DelaySync]=0;c[DelayMs]=100;c[DelayFeedback]=.5f;c[DelayMix]=1;c[DelayTone]=16000;
        PingPong d;
        check(d.prepare(48000),"delay prepare");d.set(c.values,48000,145);d.reset();
        Stereo first{},second{};for(int i=0;i<15000;++i){auto y=d.tick(i==0?Stereo{1,1}:Stereo{});if(i==4800)first=y;if(i==9600)second=y;}
        close(first.l,1,1e-6f,"left first echo at 100ms");close(first.r,0,0,"no right first echo");
        check(second.r>.4f&&second.l==0,"ping pong second echo on right");
        Reverb reverb;
        check(reverb.prepare(48000),"reverb prepare");c[ReverbMix]=1;c[ReverbDecay]=.4f;reverb.set(c.values,48000);reverb.reset();double early=0,late=0;
        for(int i=0;i<96000;++i){auto y=reverb.tick(i==0?Stereo{1,1}:Stereo{});if(i<24000)early+=y.l*y.l+y.r*y.r;if(i>72000)late+=y.l*y.l+y.r*y.r;}
        check(early>.01&&late<early*.001,"FDN tail decays");
        reverb.reset();c[ReverbDecay]=10;reverb.set(c.values,48000);
        for(int i=0;i<12000;++i)reverb.tick({.1f*tone(i,500),.1f*tone(i,500)});
        c[ReverbFreeze]=1;reverb.set(c.values,48000);double frozen=0;for(int i=0;i<144000;++i){auto y=reverb.tick({});
        check(std::isfinite(y.l)&&peak(y)<4,"freeze stable");if(i>120000)frozen+=y.l*y.l+y.r*y.r;}
        check(frozen>.001,"freeze retains tail");reverb.reset();for(int i=0;i<12000;++i)check(peak(reverb.tick({}))==0,"panic clears frozen tail");
        Chorus ch;ModulationFx mod;
        check(ch.prepare(48000)&&mod.prepare(48000),"modulation prepare");ch.set(c.values,48000);double width=0;
        for(int i=0;i<48000;++i){float x=.1f*tone(i,600);auto y=ch.tick({x,x});width+=std::abs(y.l-y.r);}check(width>1,"chorus stereo width");
        for(int mode=0;mode<2;++mode){c[ModMode]=static_cast<float>(mode);mod.set(c.values,48000);mod.reset();double change=0;
            for(int i=0;i<48000;++i){float x=.1f*tone(i,600);auto y=mod.tick({x,x});
        check(std::isfinite(y.l)&&peak(y)<2,"modulation bounded");change+=std::abs(y.l-x);}check(change>10,"phaser/flanger audible");}
    }
    for(float rate:{8000.f,44100.f,48000.f,96000.f,192000.f,384000.f}) {
        Config c; Equalizer eq; c[Eq1Frequency]=20;c[Eq1Q]=12;c[Eq1Gain]=18;
        eq.set(c.values,rate);
        for(int i=0;i<48000;++i){const auto y=eq.tick(i==64?Stereo{1,-1}:Stereo{});check(std::isfinite(y.l)&&peak(y)<10,"low frequency high Q across rates");}
    }
    {
        Spectrum s;for(int i=0;i<4096;++i)s.push({tone(i,984.375f),-tone(i,984.375f)},3);auto bins=s.analyze(48000);
        auto peakAt=static_cast<int>(std::max_element(bins.begin(),bins.end())-bins.begin());
        check(peakAt>=34&&peakAt<=37,"FFT known tone log band");
        check(bins[peakAt]>-.1f,"FFT stereo antiphase preserved");
        s.push({},5);bins=s.analyze(48000);
        check(s.track()==5&&*std::max_element(bins.begin(),bins.end())==-100,"new analyzer source clears old spectrum");
        for(int i=0;i<34000;++i)s.push({},5);
        check(s.dropped()>0,"analyzer bounded backpressure");
    }
    {
        // Parameter extremes, live rack edits and reset must remain allocation-free.
        Rack rack;
        check(rack.prepare(48000),"stress prepare");Config c;
        for(int id:enableIds)c[id]=1;
        const int before=realtimeAllocations.load();inRender=true;
        for(int iteration=0;iteration<24;++iteration){for(int p=0;p<ParameterCount;++p)if(!specs[p].integer)c[p]=(iteration&1)?specs[p].low:specs[p].high;
            for(int b=0;b<3;++b){c[Ott1Lower+b*3]=-40;c[Ott1Upper+b*3]=-12;}c[ReverbFreeze]=static_cast<float>(iteration&1);
            c[RackMode]=static_cast<float>((iteration/2)&1);std::rotate(c.order.begin(),c.order.begin()+1,c.order.end());rack.configure(c);
            for(int i=0;i<4000;++i){const float x=.02f*tone(i,1000);auto y=rack.tick({x,-x},{x,x});
        check(std::isfinite(y.l)&&std::isfinite(y.r)&&peak(y)<=64,"extreme rack output finite");}
        }
        rack.reset();inRender=false;
        check(realtimeAllocations.load()==before,"live DSP changes never allocate");
    }
    {
        auto core=std::make_unique<RenderCore>();std::vector<float> out(48000*2);Config c=preset(2);c[ReverbFreeze]=1;
        for(int i=0;i<32;++i)check(core->setEffects(0,c),"FX mailbox capacity");
        check(!core->setEffects(0,c),"FX mailbox backpressure");
        render(*core,out.data(),128);
        check(core->setEffects(0,c),"FX mailbox recycled");
        check(core->enqueue(note(0,1)),"note accepted");render(*core,out.data(),48000);
        core->panic();render(*core,out.data(),48000);
        check(energy(out)==0,"panic immediately removes all tails");
        auto target=std::make_unique<RenderCore>();auto reference=std::make_unique<RenderCore>();Config duck=preset(3);duck[ScSource]=0;
        check(target->setEffects(1,duck),"route external source");
        for(auto* r:{target.get(),reference.get()}){r->enqueue({CommandType::TrackMute,0,0,0,1});r->enqueue(note(0,1,53));r->enqueue(note(1,2,41));}
        std::vector<float> dry(out.size());render(*target,out.data(),48000);render(*reference,dry.data(),48000);
        check(energy(out,24000)<energy(dry,24000)*.6,"muted source still ducks other track");
        check(target->effectsSnapshot().sidechain[1]>3,"sidechain meter published");
        auto one=std::make_unique<RenderCore>();auto many=std::make_unique<RenderCore>();for(auto* r:{one.get(),many.get()}){r->setEffects(0,preset(2));r->enqueue(note(0,1,57,100));r->enqueue({CommandType::NoteOff,0,0,1,0,12000});}
        render(*one,out.data(),48000);for(int i=0;i<48000;i+=97)render(*many,dry.data()+i*2,std::min(97,48000-i));
        check(out==dry,"FX render independent of callback partition");
        check(realtimeAllocations.load()==0,"complete render path allocates nothing");
    }
    std::printf("PASS: %d FX assertions\n",checks);
}
