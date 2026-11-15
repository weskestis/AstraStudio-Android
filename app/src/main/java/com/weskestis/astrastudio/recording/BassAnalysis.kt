package com.weskestis.astrastudio.recording

import kotlin.math.*

data class BassResult(val hz: Double?,val confidence: Double,val rms: Double,val peak: Double,val seconds: Double,val spectrum: List<Float>) {
    val midi: Int? get()=hz?.let{(69+12*log2(it/440)).roundToInt()}
    val cents: Int? get()=hz?.let{((69+12*log2(it/440)-midi!!)*100).roundToInt()}
    val note: String get()=midi?.let{listOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B")[((it%12)+12)%12]+(it/12-1)}?:"No stable pitch"
}

/** YIN-style normalized difference on an averaged, decimated analysis signal.
 * This does not process saved audio. A periodicity estimate is not a bass classifier.
 */
object BassAnalysis {
    fun analyze(wave: WaveFile,first: Long,last: Long,check: ()->Unit={}): BassResult {
        require(first>=0&&last in (first+1)..wave.frames)
        val count=min(last-first,wave.rate*2L).toInt();val input=FloatArray(count*2);wave.read(first,count,input);check()
        var l2=0.0;var r2=0.0;var peak=0.0
        repeat(count){i->l2+=input[i*2].toDouble().pow(2);r2+=input[i*2+1].toDouble().pow(2);peak=max(peak,max(abs(input[i*2]),abs(input[i*2+1])).toDouble())}
        // Strongest channel avoids phase cancellation in wide or inverted stereo.
        val channel=if(l2>=r2)0 else 1;val step=max(1,(wave.rate/6000.0).roundToInt());val rate=wave.rate.toDouble()/step
        val x=DoubleArray(count/step){i->var v=0.0;repeat(step){v+=input[(i*step+it)*2+channel]};v/step}
        val mean=x.average();for(i in x.indices)x[i]-=mean
        val rms=sqrt(max(l2,r2)/count);val seconds=count.toDouble()/wave.rate
        var hz: Double?=null;var confidence=0.0
        val maxLag=min((rate/20).toInt(),x.size/3);val minLag=max(2,(rate/1000).toInt())
        if(rms>0.0001&&maxLag>minLag+2){
            // Use at most ~0.7 s for bounded CPU even on a two-second selection.
            val n=min(x.size-maxLag,4096);val diff=DoubleArray(maxLag+1);var sum=0.0
            for(t in 1..maxLag){if(t%32==0)check();var v=0.0;repeat(n){i->val d=x[i]-x[i+t];v+=d*d};sum+=v;diff[t]=if(sum>1e-30)v*t/sum else 1.0}
            var candidate=0
            var t=minLag
            while(t<maxLag){if(diff[t]<.15){while(t+1<=maxLag&&diff[t+1]<diff[t])t++;candidate=t;break};t++}
            if(candidate==0){candidate=(minLag..maxLag).minBy{diff[it]}}
            confidence=(1-diff[candidate]).coerceIn(0.0,1.0)
            if(confidence>=.8&&candidate>minLag&&candidate<maxLag){val a=diff[candidate-1];val b=diff[candidate];val c=diff[candidate+1];val denom=a-2*b+c
                val correction=if(abs(denom)>1e-12)(.5*(a-c)/denom).coerceIn(-1.0,1.0)else 0.0
                hz=rate/(candidate+correction)
            }
        }
        check();val size=16384;val real=DoubleArray(size);val imag=DoubleArray(size);val n=min(x.size,size)
        if(n>1)repeat(n){i->real[i]=x[i]*(.5-.5*cos(2*PI*i/(n-1)))}
        var j=0
        for(i in 1 until size){var bit=size/2;while(j and bit!=0){j=j xor bit;bit/=2};j=j xor bit;if(i<j){val v=real[i];real[i]=real[j];real[j]=v}}
        var len=2
        while(len<=size){check();val angle=-2*PI/len;val wr=cos(angle);val wi=sin(angle)
            for(start in 0 until size step len){var ar=1.0;var ai=0.0
                for(k in 0 until len/2){val a=start+k;val b=a+len/2;val br=real[b]*ar-imag[b]*ai;val bi=real[b]*ai+imag[b]*ar
                    real[b]=real[a]-br;imag[b]=imag[a]-bi;real[a]+=br;imag[a]+=bi;val next=ar*wr-ai*wi;ai=ar*wi+ai*wr;ar=next}}
            len*=2
        }
        val spectrum=List(128){k->val lo=20.0*100.0.pow(k/128.0);val hi=20.0*100.0.pow((k+1)/128.0);var magnitude=0.0
            for(i in max(1,floor(lo*size/rate).toInt())..min(size/2,ceil(hi*size/rate).toInt()))magnitude=max(magnitude,hypot(real[i],imag[i])*4/max(1,n))
            (20*log10(max(1e-6,magnitude))).coerceIn(-100.0,6.0).toFloat()}
        return BassResult(hz,confidence,rms,peak,seconds,spectrum)
    }
}
