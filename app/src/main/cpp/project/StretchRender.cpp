#include "StretchRender.h"
#include "WaveWriter.h"
#include <signalsmith-stretch/signalsmith-stretch.h>
#include <cmath>
namespace astra::project {
int stretchWave(const char* path,const Sample& sample,int64_t first,int64_t last,int64_t outputFrames,double semitones,
                std::atomic<bool>& cancel,std::atomic<float>& progress) {
    if(first<0||last<=first||last>sample.frames())return 1;
    const auto count=last-first;
    if(outputFrames<=0||outputFrames>static_cast<int64_t>(kMaxSourceFrames)||
       outputFrames<count*.5||outputFrames>count*2.0||!std::isfinite(semitones)||std::abs(semitones)>24)return 1;
    SampleCursor cursor;
    WaveWriter writer(path,sample.rate,outputFrames,true);if(!writer.good())return 2;
    if(outputFrames==count&&semitones==0){
        for(int64_t at=0;at<count;){if(cancel.load())return 3;const auto n=std::min<int64_t>(512,count-at);
            std::array<float,1024> block{};if(!sample.read(first+at,block.data(),int(n),cursor)||!writer.write({block.data(),static_cast<std::size_t>(n*2)}))return 2;
            at+=n;progress=static_cast<float>(at)/count;}
        return writer.finish()?0:2;
    }
    // Double precision analysis/phase reconstruction; float only at the file boundary.
    signalsmith::stretch::SignalsmithStretch<double> stretch(0x41535452);
    stretch.presetDefault(2,static_cast<double>(sample.rate));stretch.setTransposeSemitones(semitones);
    const int inputLatency=stretch.inputLatency(),outputLatency=stretch.outputLatency();
    const int capacity=std::max({inputLatency,outputLatency,2048});
    std::array<std::vector<double>,2> in,out;for(auto& x:in)x.resize(capacity);for(auto& x:out)x.resize(capacity);
    auto read=[&](int64_t offset,int n){for(int i=0;i<n;++i){const auto at=offset+i;float pair[2]{};if(at>=0&&at<count)sample.read(first+at,pair,1,cursor);for(int c=0;c<2;++c)in[c][i]=pair[c];}};
    read(0,inputLatency);stretch.seek(in,inputLatency,static_cast<double>(count)/outputFrames);
    int64_t generated=0,consumed=0;std::array<float,1024> interleaved{};
    auto write=[&](int n){int used=0;for(int i=0;i<n;++i){if(generated>=outputLatency&&generated<outputLatency+outputFrames){for(int c=0;c<2;++c)interleaved[used++]=static_cast<float>(out[c][i]);}++generated;}
        return used==0||writer.write({interleaved.data(),static_cast<std::size_t>(used)});};
    for(int64_t done=0;done<outputFrames;){
        if(cancel.load())return 3;
        const int n=static_cast<int>(std::min<int64_t>(512,outputFrames-done));
        const auto end=static_cast<int64_t>(std::llround(static_cast<long double>(done+n)*count/outputFrames));const int input=static_cast<int>(end-consumed);
        read(inputLatency+consumed,input);stretch.process(in,input,out,n);if(!write(n))return 2;
        consumed=end;done+=n;progress=static_cast<float>(done)/(outputFrames+outputLatency);
    }
    // flush() must run once: its output is already the final latency tail.
    stretch.flush(out,outputLatency);
    for(int at=0;at<outputLatency;){if(cancel.load())return 3;
        const int n=std::min(512,outputLatency-at);int used=0;
        for(int i=0;i<n;++i){if(generated>=outputLatency&&generated<outputLatency+outputFrames)for(int c=0;c<2;++c)interleaved[used++]=static_cast<float>(out[c][at+i]);++generated;}
        if(used&&!writer.write({interleaved.data(),static_cast<std::size_t>(used)}))return 2;
        at+=n;
    }
    if(sample.failed()||!writer.finish())return 2;
    progress=1;return 0;
}
}
