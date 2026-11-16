package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.recording.WaveFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Source-frame markers: the first and last entries are the selection's fixed file edges. */
object Slicing {
    fun valid(markers: List<Long>,frames: Long)=markers.isEmpty()||(markers.size in 2..17&&markers.first()==0L&&markers.last()==frames&&markers.zipWithNext().all{(a,b)->a>=0&&a<b})
    fun equal(frames: Long,count: Int): List<Long> {require(frames>0&&count in 1..16);val n=minOf(frames,count.toLong()).toInt();return (0..n).map{frames*it/n}}
    fun add(markers: List<Long>,frames: Long,at: Long): List<Long> {
        require(valid(markers,frames));val old=markers.ifEmpty{listOf(0,frames)}
        require(old.size<17&&at in 1 until frames&&at !in old){"Use a new interior position; a rack has at most 16 slices."}
        return (old+at).sorted()
    }
    fun move(markers: List<Long>,index: Int,at: Long): List<Long> {
        require(index in 1 until markers.lastIndex&&at>markers[index-1]&&at<markers[index+1]){"Keep this marker between its neighbors."}
        return markers.toMutableList().also{it[index]=at}
    }
    fun remove(markers: List<Long>,index: Int): List<Long>{require(index in 1 until markers.lastIndex){"The file edges stay fixed."};return markers.filterIndexed{i,_->i!=index}}
    fun zero(wave: WaveFile,at: Long,radius: Int,first: Long=0,last: Long=wave.frames): Long {
        require(first>=0&&last<=wave.frames&&first<last&&radius in 1..38400)
        val lo=max(first,at-radius);val hi=min(last,at+radius+1);if(hi<=lo)return at.coerceIn(first,last)
        val begin=max(0,lo-1);val data=FloatArray(((hi-begin)*2).toInt());wave.read(begin,(hi-begin).toInt(),data)
        var best=at.coerceIn(lo,hi-1);var score=Double.POSITIVE_INFINITY
        for(frame in lo until hi){val i=((frame-begin)*2).toInt();val level=max(abs(data[i]),abs(data[i+1])).toDouble()
            val crossing=i>=2&&((data[i-2]<=0&&data[i]>=0)||(data[i-2]>=0&&data[i]<=0))&&((data[i-1]<=0&&data[i+1]>=0)||(data[i-1]>=0&&data[i+1]<=0))
            val value=level+(if(crossing)0.0 else .02)+abs(frame-at).toDouble()/max(1,radius)*.00001
            if(value<score){score=value;best=frame}
        };return best
    }
    fun snap(file: File,z: SampleZone,cancel: AtomicBoolean): SampleZone=WaveFile(file).use{w->
        check(!cancel.get()){"Cancelled."};val radius=max(1,w.rate/500)
        val first=zero(w,z.first,radius,0,z.last);val last=if(first+1==w.frames)w.frames else zero(w,z.last,radius,first+1,w.frames).coerceAtLeast(first+1)
        val lf=zero(w,z.loopFirst,radius,first,last);val ll=if(lf+1==last)last else zero(w,z.loopLast,radius,lf+1,last).coerceAtLeast(lf+1)
        z.range(first,last,lf,ll)
    }
    /** Peak-envelope attacks with a 30 ms release, a 25 ms refractory period and bounded candidates. */
    fun detect(file: File,maxSlices: Int,sensitivity: Float,cancel: AtomicBoolean,progress: (Float)->Unit={}): List<Long> = WaveFile(file).use{wave->
        require(maxSlices in 1..16&&sensitivity.isFinite()&&sensitivity in 0f..1f)
        data class Hit(val at: Long,val score: Float)
        val candidates=mutableListOf<Hit>();val data=FloatArray(8192*2);val window=max(1,wave.rate/500);val gap=max(1,wave.rate/40)
        val release=exp(-window.toDouble()/(wave.rate*.03)).toFloat();var background=0f;var peak=0f;var global=0f;var inWindow=0;var percent=-1;var at=0L;var pending: Hit?=null
        fun keep(hit: Hit){if(hit.at>0){candidates+=hit;if(candidates.size>128)candidates.removeAt(candidates.indices.minBy{candidates[it].score})}}
        fun finishWindow(position: Long){
            val score=(peak-background).coerceAtLeast(0f);background=max(peak,background*release)
            if(score>1e-6f){val next=Hit(position,score);val old=pending
                if(old==null)pending=next else if(position-old.at<gap){if(score>old.score)pending=next}else{keep(old);pending=next}}
            peak=0f;inWindow=0
        }
        while(at<wave.frames){check(!cancel.get()){"Cancelled."};val n=wave.read(at,min(8192L,wave.frames-at).toInt(),data)
            repeat(n){i->val v=max(abs(data[2*i]),abs(data[2*i+1]));peak=max(peak,v);global=max(global,v);inWindow++
                if(inWindow==window)finishWindow(at+i-window+1)}
            at+=n;val nextPercent=(100.0*at/wave.frames).toInt();if(nextPercent!=percent){percent=nextPercent;progress(at.toFloat()/wave.frames)}
        }
        if(inWindow>0)finishWindow(at-inWindow);pending?.let(::keep)
        val threshold=global*(.015f+(1-sensitivity)*.25f);val chosen=mutableListOf<Long>()
        for(hit in candidates.sortedByDescending{it.score})if(chosen.size<maxSlices-1&&hit.score>=threshold&&hit.at>=gap&&wave.frames-hit.at>=gap&&chosen.all{abs(it-hit.at)>=gap})chosen+=hit.at
        val points=chosen.sorted().map{zero(wave,it,max(1,wave.rate/1000))}.filter{it>0&&it<wave.frames}.distinct()
        (listOf(0L)+points+wave.frames).also{require(valid(it,wave.frames))}
    }
    fun instrument(a: AudioAsset,old: SampleInstrument): SampleInstrument {
        require(valid(a.slices,a.frames)&&a.slices.isNotEmpty()){"Add slice markers first."}
        val pads=List(16){pad->if(pad<a.slices.size-1){val first=a.slices[pad];val last=a.slices[pad+1];val edge=minOf((a.rate*.0005).roundToLong(),(last-first)/8)
            SampleZone(root=36+pad,attack=0f,sustain=1f,oneShot=true).load(a).range(first,last,first,last).copy(fadeIn=edge,fadeOut=edge)}else SampleZone(root=36+pad,oneShot=true)}
        return old.copy(mode=2,pads=pads,layers=old.layers.mapIndexed{i,row->if(i==0)row else emptyList()})
    }
    fun clip(a: AudioAsset,slot: Int,bpm: Float): Clip {
        require(a.slices.isNotEmpty()&&valid(a.slices,a.frames))
        require(a.slices.zipWithNext().all{(f,l)->(l-f).toDouble()/a.rate*bpm/60>=1.0/960}){"Each slice needs at least one MIDI tick to create a clip."}
        val length=max(.0625,a.frames.toDouble()/a.rate*bpm/60);require(length<=65536){"This recording is too long for one MIDI clip."}
        var c=Clip(slot,(a.name+" slices").take(64),length)
        a.slices.zipWithNext().forEachIndexed{i,(first,last)->c=c.add(first.toDouble()/a.rate*bpm/60,36+i,max(1.0/960,(last-first).toDouble()/a.rate*bpm/60),1f)}
        return c
    }
}
