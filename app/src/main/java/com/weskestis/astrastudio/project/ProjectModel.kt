package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import kotlin.math.*

data class MidiNote(val id: Int, val beat: Double, val length: Double, val pitch: Int, val velocity: Float=.85f)
data class AutoPoint(val beat: Double,val value: Float)
data class AudioAsset(val id: Int,val name: String,val file: String,val rate: Int,val frames: Long,val waveform: List<Float>)
data class Clip(val slot: Int,val name: String="MIDI clip",val length: Double=16.0,
    val notes: List<MidiNote> = emptyList(),val automation: List<List<AutoPoint>> = List(4){emptyList()},
    val asset: Int = -1,val first: Long = 0,val last: Long = 0,val root: Int = 5,val minor: Boolean = true) {
    val track get()=slot%16
    val scene get()=slot/16
    val isAudio get()=asset>=0
    fun note(id: Int)=notes.firstOrNull{it.id==id}
    fun nextId()=(notes.maxOfOrNull{it.id}?:0)+1
    fun add(beat: Double,pitch: Int,duration: Double,velocity: Float=.85f): Clip {
        require(!isAudio&&notes.size<256){"This MIDI clip has reached 256 notes."}
        val start=beat.coerceIn(0.0,length-1.0/960)
        return copy(notes=notes+MidiNote(nextId(),start,duration.coerceIn(1.0/960,length-start),pitch.coerceIn(0,127),velocity.coerceIn(.001f,1f)))
    }
    fun editNote(id: Int,beat: Double?=null,pitch: Int?=null,duration: Double?=null,velocity: Float?=null): Clip = copy(notes=notes.map{
        if(it.id!=id)it else {val start=(beat?:it.beat).coerceIn(0.0,length-1.0/960)
            it.copy(beat=start,pitch=(pitch?:it.pitch).coerceIn(0,127),length=(duration?:it.length).coerceIn(1.0/960,length-start),velocity=(velocity?:it.velocity).coerceIn(.001f,1f))}})
    fun quantize(grid: Double): Clip = copy(notes=notes.map{val start=(round(it.beat/grid)*grid).coerceIn(0.0,length-grid.coerceAtMost(length));
        it.copy(beat=start,length=(round(it.length/grid)*grid).coerceAtLeast(grid).coerceAtMost(length-start))})
    fun legato(): Clip = copy(notes=notes.map{n->val next=notes.filter{it.beat>n.beat+1e-8}.minOfOrNull{it.beat}?:length;n.copy(length=next-n.beat)})
    fun resize(beats: Double): Clip {val end=beats.coerceIn(.0625,65536.0);return copy(length=end,
        notes=notes.filter{it.beat<end}.map{it.copy(length=min(it.length,end-it.beat))},
        automation=automation.map{lane->lane.filter{it.beat<=end}})}
    fun point(lane: Int,beat: Double,value: Float,grid: Double): Clip {
        require(lane in 0..3)
        val at=(round(beat/grid)*grid).coerceIn(0.0,length)
        val points=automation[lane].filter{abs(it.beat-at)>1e-7}+AutoPoint(at,value.coerceIn(autoLow[lane],autoHigh[lane]))
        require(points.size<=64){"Each automation lane holds up to 64 points."}
        return copy(automation=automation.toMutableList().also{it[lane]=points.sortedBy{p->p.beat}})
    }
    companion object {
        val autoNames=listOf("Volume","Pan","Filter 1 cutoff","FX macro 1")
        val autoLow=listOf(0f,-1f,20f,0f)
        val autoHigh=listOf(2f,1f,18000f,1f)
    }
}
data class Region(val id: Int,val slot: Int,val beat: Double,val length: Double,val offset: Double=0.0,val audioOffset: Long=0)
data class Project(val name: String="Untitled",val numerator: Int=4,val denominator: Int=4,
    val mode: Int=0,val quantization: Int=0,val loop: Boolean=false,val loopStart: Double=0.0,val loopEnd: Double=16.0,
    val clips: List<Clip> = emptyList(),val regions: List<Region> = emptyList(),val assets: List<AudioAsset> = emptyList()) {
    val barBeats get()=numerator*4.0/denominator
    val launchBeats get()=when(quantization){1->1.0;2->.5;else->barBeats}
    val endBeat get()=regions.maxOfOrNull{it.beat+it.length}?:0.0
    fun clip(slot: Int)=clips.firstOrNull{it.slot==slot}
    fun asset(id: Int)=assets.firstOrNull{it.id==id}
    fun put(clip: Clip)=copy(clips=clips.filter{it.slot!=clip.slot}+clip)
    fun remove(slot: Int)=copy(clips=clips.filter{it.slot!=slot},regions=regions.filter{it.slot!=slot})
    fun emptySlot(track: Int)=(0..7).map{it*16+track}.firstOrNull{clip(it)==null}
    fun duplicate(slot: Int,target: Int): Project {val source=clip(slot)?:error("Choose a clip first.");require(clip(target)==null){"That slot is occupied."};return put(source.copy(slot=target,name=(source.name+" copy").take(64)))}
    fun place(slot: Int,at: Double): Project {val c=clip(slot)?:error("Choose a clip first.");require(regions.size<512){"The arrangement holds up to 512 regions."}
        val r=Region((regions.maxOfOrNull{it.id}?:0)+1,slot,at.coerceAtLeast(0.0),c.length)
        val next=copy(regions=regions+r);require(next.valid()){ "That region overlaps another clip or exceeds the timeline." };return next}
    fun editRegion(id: Int,beat: Double?=null,length: Double?=null): Project {val next=copy(regions=regions.map{if(it.id==id)it.copy(beat=beat?:it.beat,length=length?:it.length) else it});require(next.valid()){ "Regions on one track cannot overlap." };return next}
    fun duplicateRegion(id: Int): Project {val r=regions.firstOrNull{it.id==id}?:error("Choose a region.")
        val next=copy(regions=regions+r.copy(id=(regions.maxOfOrNull{it.id}?:0)+1,beat=r.beat+r.length));require(next.valid()){ "The duplicate would overlap another region." };return next}
    fun splitRegion(id: Int,at: Double,bpm: Float): Project {
        val r=regions.firstOrNull{it.id==id}?:error("Choose a region.");val c=clip(r.slot)!!
        require(at>r.beat+1.0/960&&at<r.beat+r.length-1.0/960){"Put the cursor inside the selected region."}
        var left=at-r.beat;var offset=r.audioOffset
        if(c.isAudio){val a=asset(c.asset)!!;val frames=round(left*60/bpm*a.rate).toLong();left=frames.toDouble()/a.rate*bpm/60;offset+=frames;require(offset<c.last-c.first){"Split falls after the audio ends."}}
        val b=r.copy(id=(regions.maxOfOrNull{it.id}?:0)+1,beat=r.beat+left,length=r.length-left,
            offset=if(c.isAudio)0.0 else (r.offset+left)%c.length,audioOffset=offset)
        val next=copy(regions=regions.filter{it.id!=id}+r.copy(length=left)+b);require(next.valid());return next
    }
    fun nativeWire(): DoubleArray {
        val data=ArrayList<Double>();data.addAll(listOf(1.0,mode.toDouble(),numerator.toDouble(),denominator.toDouble(),if(loop)1.0 else 0.0,loopStart,loopEnd,clips.size.toDouble(),regions.size.toDouble()))
        for(c in clips.sortedBy{it.slot}) {
            data.addAll(listOf(c.slot.toDouble(),c.length,c.asset.toDouble(),c.first.toDouble(),c.last.toDouble(),c.notes.size.toDouble(),c.automation.sumOf{it.size}.toDouble()))
            for(n in c.notes)data.addAll(listOf(n.beat,n.length,n.pitch.toDouble(),n.velocity.toDouble()))
            c.automation.forEachIndexed{lane,points->points.forEach{data.addAll(listOf(lane.toDouble(),it.beat,it.value.toDouble()))}}
        }
        for(r in regions)data.addAll(listOf(r.id.toDouble(),r.slot.toDouble(),r.beat,r.length,r.offset,r.audioOffset.toDouble()))
        return data.toDoubleArray()
    }
    fun valid(): Boolean {
        if(name.isBlank()||name.length>64||name.any{it.isISOControl()}||numerator !in 1..12||denominator !in listOf(2,4,8)||mode !in 0..1||quantization !in 0..2)return false
        if(!loopStart.isFinite()||!loopEnd.isFinite()||loopStart<0||loopEnd<=loopStart||loopEnd>65536)return false
        if(clips.size>128||clips.map{it.slot}.toSet().size!=clips.size||regions.size>512||regions.map{it.id}.toSet().size!=regions.size)return false
        if(assets.size>64||assets.map{it.id}.toSet().size!=assets.size||assets.sumOf{it.frames}>67108864)return false
        for(a in assets)if(a.id !in 0..63||a.rate !in 8000..384000||a.frames !in 1..67108864||!a.file.matches(Regex("[A-Za-z0-9_-]{1,100}\\.wav"))||a.name.length>128||a.waveform.size!=1024||a.waveform.any{!it.isFinite()||abs(it)>8})return false
        for(c in clips){
            if(c.slot !in 0..127||c.name.isBlank()||c.name.length>64||!c.length.isFinite()||c.length !in .0625..65536.0||c.root !in 0..11||c.asset !in -1..63||c.first<0||c.last<0||c.notes.size>256||c.notes.map{it.id}.toSet().size!=c.notes.size||c.automation.size!=4)return false
            if(c.isAudio){val a=asset(c.asset)?:return false;if(c.last<=c.first||c.last>a.frames||c.notes.isNotEmpty())return false}
            for(n in c.notes)if(n.id !in 1..1000000000||!n.beat.isFinite()||!n.length.isFinite()||n.beat<0||n.length<1.0/960||n.beat+n.length>c.length+1e-8||n.pitch !in 0..127||!n.velocity.isFinite()||n.velocity !in .001f..1f)return false
            for(lane in 0..3){val points=c.automation[lane];if(points.size>64)return false;var last=-1.0
                for(p in points){if(!p.beat.isFinite()||p.beat !in 0.0..c.length||p.beat<=last||!p.value.isFinite()||p.value !in Clip.autoLow[lane]..Clip.autoHigh[lane])return false;last=p.beat}}
        }
        for(r in regions){val c=clip(r.slot)?:return false
            if(r.id !in 1..1000000000||!r.beat.isFinite()||!r.length.isFinite()||!r.offset.isFinite()||r.beat<0||r.length<1.0/960||r.beat+r.length>65536||r.offset !in 0.0..65536.0||r.audioOffset<0)return false
            if(c.isAudio&&r.audioOffset>=c.last-c.first)return false
        }
        for(track in 0..15){var end=0.0;for(r in regions.filter{it.slot%16==track}.sortedBy{it.beat}){if(r.beat<end-1e-8)return false;end=r.beat+r.length}}
        return true
    }
}
data class ProjectDocument(val project: Project,val bpm: Float,val master: Float,val limiterDrive: Float,val limiterRelease: Float,
    val tracks: List<TrackControls>,val patches: List<SynthPatch>,val effects: List<FxConfig>) {
    fun valid()=project.valid()&&bpm.isFinite()&&bpm in 30f..300f&&master.isFinite()&&master in 0f..1f&&limiterDrive.isFinite()&&limiterDrive in 0f..24f&&limiterRelease.isFinite()&&limiterRelease in 20f..500f&&tracks.size==16&&patches.size==16&&effects.size==16&&tracks.all{
        it.gain.isFinite()&&it.gain in 0f..2f&&it.pan.isFinite()&&it.pan in -1f..1f&&it.sendA.isFinite()&&it.sendA in 0f..1f&&it.sendB.isFinite()&&it.sendB in 0f..1f&&it.name.length<=32}
    fun mixWire()=(listOf(bpm,master,limiterDrive,limiterRelease)+tracks.flatMap{listOf(it.gain,it.pan,if(it.mute)1f else 0f,if(it.solo)1f else 0f,it.sendA,it.sendB)}).toFloatArray()
}
class ProjectHistory {
    private val undo=ArrayDeque<ProjectDocument>();private val redo=ArrayDeque<ProjectDocument>();private var key="";private var time=0L;private var group=false
    val canUndo get()=undo.isNotEmpty();val canRedo get()=redo.isNotEmpty()
    fun commit(old: ProjectDocument,next: ProjectDocument,merge: String="",now: Long=System.nanoTime()/1000000) {
        if(old==next)return
        if(!group&&(merge.isEmpty()||merge!=key||now-time>350)){undo.addLast(old);if(undo.size>64)undo.removeFirst()}
        redo.clear();key=merge;time=now
    }
    fun begin(current: ProjectDocument){if(!group){undo.addLast(current);if(undo.size>64)undo.removeFirst();redo.clear();group=true}}
    fun end(){group=false;key=""}
    fun undo(current: ProjectDocument): ProjectDocument? {end();if(undo.isEmpty())return null;redo.addLast(current);return undo.removeLast()}
    fun redo(current: ProjectDocument): ProjectDocument? {end();if(redo.isEmpty())return null;undo.addLast(current);return redo.removeLast()}
    fun clear(){undo.clear();redo.clear();end()}
}
data class SequenceStats(val active: List<Int> = List(16){-1},val queued: List<Int> = List(16){-2},val phase: List<Float> = List(16){0f},val mode: Int=0)
