package com.weskestis.astrastudio.project

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.math.ceil

data class FrozenTrack(val track: Int,val asset: Int,val musicFrames: Long,val fingerprint: String)

/** A freeze replaces only Arrangement source rendering. Inserts, sends and faders stay live. */
object FrozenTracks {
    val rates=listOf(48000,96000,192000)
    fun fingerprint(d: ProjectDocument,track: Int): String {
        require(track in 0 until ProjectLimits.TRACKS)
        val p=d.project;val digest=MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(object: OutputStream(){override fun write(b: Int){};override fun write(b: ByteArray,off: Int,len: Int){}},digest)).use { out->
            out.writeInt(1);out.writeInt(track);out.writeFloat(d.bpm);out.writeDouble(p.endBeat)
            out.writeBoolean(p.tempoEnabled)
            if(p.tempoEnabled){out.writeInt(p.tempoPoints.size);p.tempoPoints.forEach{out.writeDouble(it.beat);out.writeFloat(it.bpm);out.writeBoolean(it.ramp)}}
            d.patches[track].wire.forEach(out::writeFloat)
            val instrument=p.instruments[track];out.writeInt(instrument.mode)
            (instrument.sample.wire()+instrument.pads.flatMap{it.wire()}).forEach(out::writeDouble)
            val clips=p.clips.filter{it.track==track}.sortedBy{it.slot};out.writeInt(clips.size)
            clips.forEach { c->
                out.writeInt(c.slot);out.writeDouble(c.length);out.writeInt(c.asset);out.writeLong(c.first);out.writeLong(c.last)
                out.writeBoolean(c.warp);out.writeDouble(c.sourceBpm);out.writeLong(c.warpStart);out.writeDouble(c.pitch)
                out.writeInt(c.notes.size);c.notes.forEach{out.writeDouble(it.beat);out.writeDouble(it.length);out.writeInt(it.pitch);out.writeFloat(it.velocity)}
                // Filter automation affects source synthesis. Other lanes remain live after freezing.
                out.writeInt(c.automation[2].size);c.automation[2].forEach{out.writeDouble(it.beat);out.writeFloat(it.value)}
            }
            val regions=p.regions.filter{it.slot%ProjectLimits.TRACKS==track}.sortedBy{it.beat};out.writeInt(regions.size)
            regions.forEach{out.writeInt(it.id);out.writeInt(it.slot);out.writeDouble(it.beat);out.writeDouble(it.length);out.writeDouble(it.offset);out.writeLong(it.audioOffset)}
            val used=(clips.map{it.asset}+listOf(instrument.sample.asset)+instrument.pads.map{it.asset}).filter{it>=0}.distinct().sorted()
            used.forEach{id->val a=requireNotNull(p.asset(id));out.writeInt(id);out.writeInt(a.rate);out.writeLong(a.frames)}
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun retain(d: ProjectDocument,retained: List<FrozenTrack>,releaseUnused: Boolean): ProjectDocument {
        val removed=d.project.frozen.filter{it !in retained}.map{it.asset}.toSet()
        val used=(d.project.clips.map{it.asset}+d.project.instruments.flatMap{listOf(it.sample.asset)+it.pads.map{z->z.asset}}+retained.map{it.asset}).toSet()
        val assets=if(releaseUnused)d.project.assets.filter{it.id !in removed||it.id in used}else d.project.assets
        return d.copy(project=d.project.copy(frozen=retained,assets=assets))
    }
    fun unfreeze(d: ProjectDocument,track: Int)=retain(d,d.project.frozen.filter{it.track!=track},true)
    fun reconcile(d: ProjectDocument,releaseUnused: Boolean=false): ProjectDocument {
        if(d.project.frozen.isEmpty())return d
        val retained=d.project.frozen.filter{it.fingerprint==fingerprint(d,it.track)}
        return if(retained.size==d.project.frozen.size)d else retain(d,retained,releaseUnused)
    }
    fun frames(d: ProjectDocument,rate: Int,tail: Float): Long {
        require(rate in rates&&tail.isFinite()&&tail in 0f..30f)
        return musicFrames(d,rate)+ceil((tail*rate).toDouble()).toLong()
    }
    fun musicFrames(d: ProjectDocument,rate: Int)=ceil(d.project.timelineSeconds(0.0,d.project.endBeat,d.bpm)*rate).toLong()
    fun check(d: ProjectDocument,track: Int,rate: Int,tail: Float){
        require(d.valid()&&track in 0 until ProjectLimits.TRACKS)
        require(d.project.frozen.none{it.track==track}){"Unfreeze this track first."}
        require(d.project.regions.any{it.slot%ProjectLimits.TRACKS==track}){"Place this track's clips in Arrangement first."}
        require(AudioStorage.canAdd(d.project,frames(d,rate,tail))){"Freeze needs a free sample slot and space within the 2 GiB file / 8 GiB source limits. Choose a lower freeze rate or a shorter arrangement."}
    }
}
