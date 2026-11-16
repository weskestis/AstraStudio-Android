package com.weskestis.astrastudio.project

import kotlin.math.*

data class TempoPoint(val beat: Double,val bpm: Float,val ramp: Boolean=false)
object TempoMap {
    fun valid(points: List<TempoPoint>,enabled: Boolean): Boolean {
        if(points.size>128||(enabled&&points.isEmpty())||(points.isNotEmpty()&&points.first().beat!=0.0))return false
        var previous=-1.0
        for(p in points){if(!p.beat.isFinite()||p.beat<=previous||p.beat>65536||!p.bpm.isFinite()||p.bpm !in 30f..300f)return false;previous=p.beat}
        return true
    }
    private data class Segment(val bpm: Double,val slope: Double,val end: Double)
    private fun segment(points: List<TempoPoint>,beat: Double,fallback: Float): Segment {
        if(points.isEmpty())return Segment(fallback.toDouble(),0.0,Double.POSITIVE_INFINITY)
        val index=points.indexOfLast{it.beat<=beat}.coerceAtLeast(0);val p=points[index];val next=points.getOrNull(index+1)
        val slope=if(p.ramp&&next!=null)(next.bpm.toDouble()-p.bpm.toDouble())/(next.beat-p.beat)else 0.0
        return Segment(p.bpm+slope*(beat-p.beat),slope,next?.beat?:Double.POSITIVE_INFINITY)
    }
    fun bpmAt(points: List<TempoPoint>,beat: Double,fallback: Float)=segment(points,beat,fallback).bpm
    private fun duration(s: Segment,beats: Double)=if(abs(s.slope)<1e-12)60*beats/s.bpm else 60*ln1p(s.slope*beats/s.bpm)/s.slope
    fun seconds(points: List<TempoPoint>,first: Double,last: Double,fallback: Float): Double {
        require(first.isFinite()&&last.isFinite()&&first>=0&&last>=0&&fallback.isFinite()&&fallback in 30f..300f)
        if(last<first)return -seconds(points,last,first,fallback)
        var at=first;var result=0.0
        while(at<last){val s=segment(points,at,fallback);val end=min(last,s.end);result+=duration(s,end-at);at=end}
        return result
    }
    fun advance(points: List<TempoPoint>,beat: Double,seconds: Double,fallback: Float): Double {
        require(beat.isFinite()&&beat>=0&&seconds.isFinite()&&seconds>=0&&fallback.isFinite()&&fallback in 30f..300f)
        var at=beat;var remaining=seconds
        while(remaining>0){val s=segment(points,at,fallback);val available=if(s.end.isFinite())duration(s,s.end-at)else Double.POSITIVE_INFINITY
            if(remaining>=available){at=s.end;remaining-=available}else{at+=if(abs(s.slope)<1e-12)remaining*s.bpm/60 else s.bpm*expm1(s.slope*remaining/60)/s.slope;break}}
        return at
    }
}

object StretchSettings {
    fun outputFrames(project: Project,clip: Clip,bpm: Float,beats: Double,semitones: Double): Long {
        require(clip.isAudio){"Resample MIDI to audio before stretching."}
        val a=project.asset(clip.asset)?:error("Missing audio sample.")
        require(bpm.isFinite()&&bpm in 30f..300f&&beats.isFinite()&&beats in .0625..65536.0&&semitones.isFinite()&&semitones in -24.0..24.0){"Choose a valid length and pitch (−24 to +24 semitones)."}
        val frames=(beats*60/bpm*a.rate).roundToLong();val input=clip.last-clip.first
        require(frames>0&&frames>=input*.5&&frames<=input*2.0){"Stretch length must be 50–200% of the trimmed source."}
        require(AudioStorage.canAdd(project,frames)){"This render exceeds the 2 GiB WAV, 8 GiB source-frame or 64 sample limits."}
        return frames
    }
}
