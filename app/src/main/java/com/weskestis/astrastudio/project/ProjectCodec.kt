package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import org.json.JSONArray
import org.json.JSONObject

object ProjectCodec {
    const val MAX_JSON_BYTES=ProjectLimits.JSON_BYTES
    private fun array(values: Iterable<*>)=JSONArray().also{a->values.forEach{a.put(it)}}
    fun encode(d: ProjectDocument): String {
        require(d.valid())
        val p=d.project
        val root=JSONObject().put("format","astra-studio").put("version",7).put("trackCount",ProjectLimits.TRACKS).put("name",p.name)
            .put("bpm",d.bpm.toDouble()).put("master",d.master.toDouble()).put("limiterDrive",d.limiterDrive.toDouble()).put("limiterRelease",d.limiterRelease.toDouble())
            .put("signature",array(listOf(p.numerator,p.denominator))).put("mode",p.mode).put("quantization",p.quantization)
            .put("tempoEnabled",p.tempoEnabled).put("tempoPoints",array(p.tempoPoints.map{array(listOf(it.beat,it.bpm,if(it.ramp)1 else 0))}))
            .put("loop",p.loop).put("loopStart",p.loopStart).put("loopEnd",p.loopEnd)
            .put("tracks",array(d.tracks.map{JSONObject().put("name",it.name).put("gain",it.gain.toDouble()).put("pan",it.pan.toDouble()).put("mute",it.mute).put("solo",it.solo).put("armed",it.armed).put("sendA",it.sendA.toDouble()).put("sendB",it.sendB.toDouble())}))
            .put("patches",array(d.patches.map{it.encode()})).put("effects",array(d.effects.map{it.encode()}))
            .put("instruments",array(p.instruments.map{array(listOf(it.mode)+it.sample.wire()+it.pads.flatMap{pad->pad.wire()})}))
            .put("assets",array(p.assets.map{JSONObject().put("id",it.id).put("name",it.name).put("file",it.file).put("rate",it.rate).put("frames",it.frames).put("waveform",array(it.waveform)).put("favorite",it.favorite).put("group",it.group)}))
            .put("clips",array(p.clips.map{c->JSONObject().put("slot",c.slot).put("name",c.name).put("length",c.length).put("asset",c.asset).put("first",c.first).put("last",c.last).put("root",c.root).put("minor",c.minor)
                .put("warp",c.warp).put("sourceBpm",c.sourceBpm).put("warpStart",c.warpStart).put("pitch",c.pitch)
                .put("notes",array(c.notes.map{array(listOf(it.id,it.beat,it.length,it.pitch,it.velocity))}))
                .put("automation",array(c.automation.map{lane->array(lane.map{array(listOf(it.beat,it.value))})}))}))
            .put("frozen",array(p.frozen.map{array(listOf(it.track,it.asset,it.musicFrames,it.fingerprint))}))
            .put("regions",array(p.regions.map{array(listOf(it.id,it.slot,it.beat,it.length,it.offset,it.audioOffset))}))
        return root.toString().also{require(it.toByteArray(Charsets.UTF_8).size<=MAX_JSON_BYTES){"Project description is too large."}}
    }
    private fun JSONArray.items(max: Int): IntRange {require(length()<=max){"Project exceeds supported limits."};return 0 until length()}
    private fun JSONObject.int(key: String): Int {val v=getDouble(key);require(v.isFinite()&&v==kotlin.math.floor(v)&&v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble());return v.toInt()}
    private fun JSONArray.int(index: Int): Int {val v=getDouble(index);require(v.isFinite()&&v==kotlin.math.floor(v)&&v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble());return v.toInt()}
    private fun JSONObject.frame(key: String): Long {val v=getDouble(key);require(v.isFinite()&&v==kotlin.math.floor(v)&&v in 0.0..AudioStorage.MAX_ASSET_FRAMES.toDouble());return v.toLong()}
    private fun JSONArray.frame(index: Int): Long {val v=getDouble(index);require(v.isFinite()&&v==kotlin.math.floor(v)&&v in 0.0..AudioStorage.MAX_ASSET_FRAMES.toDouble());return v.toLong()}
    fun decode(text: String): ProjectDocument {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_JSON_BYTES){"Project description is too large."}
        val r=JSONObject(text);require(r.getString("format")=="astra-studio"&&r.int("version") in 1..7){"Unsupported project format/version."}
        val version=r.int("version")
        val storedTracks=if(version<6)16 else ProjectLimits.TRACKS
        if(version>=6)require(r.int("trackCount")==ProjectLimits.TRACKS){"Unsupported track layout."}
        fun slot(value: Int): Int {
            require(value in 0 until storedTracks*ProjectLimits.SCENES){"Invalid clip slot."}
            return value/storedTracks*ProjectLimits.TRACKS+value%storedTracks
        }
        fun <T> expanded(items: List<T>,default: ()->T)=items+List(ProjectLimits.TRACKS-items.size){default()}
        val signature=r.getJSONArray("signature");require(signature.length()==2)
        val clips=r.getJSONArray("clips").let{a->a.items(storedTracks*ProjectLimits.SCENES).map{i->val c=a.getJSONObject(i);val notes=c.getJSONArray("notes");val lanes=c.getJSONArray("automation");require(lanes.length()==4)
            Clip(slot(c.int("slot")),c.getString("name"),c.getDouble("length"),notes.items(256).map{j->val n=notes.getJSONArray(j);require(n.length()==5);MidiNote(n.int(0),n.getDouble(1),n.getDouble(2),n.int(3),n.getDouble(4).toFloat())},
                (0..3).map{lane->lanes.getJSONArray(lane).let{points->points.items(64).map{j->val p=points.getJSONArray(j);require(p.length()==2);AutoPoint(p.getDouble(0),p.getDouble(1).toFloat())}}},
                c.int("asset"),c.frame("first"),c.frame("last"),c.int("root"),c.getBoolean("minor"),
                if(r.int("version")>=4)c.getBoolean("warp")else false,if(r.int("version")>=4)c.getDouble("sourceBpm")else 120.0,
                if(r.int("version")>=4)c.frame("warpStart")else 0,if(r.int("version")>=4)c.getDouble("pitch")else 0.0)}}
        val regions=r.getJSONArray("regions").let{a->a.items(if(version<6)512 else ProjectLimits.REGIONS).map{i->val x=a.getJSONArray(i);require(x.length()==6);Region(x.int(0),slot(x.int(1)),x.getDouble(2),x.getDouble(3),x.getDouble(4),x.frame(5))}}
        val assets=r.getJSONArray("assets").let{a->a.items(64).map{i->val x=a.getJSONObject(i);val w=x.getJSONArray("waveform");require(w.length()==1024);AudioAsset(x.int("id"),x.getString("name"),x.getString("file"),x.int("rate"),x.frame("frames"),(0 until 1024).map{w.getDouble(it).toFloat()},x.optBoolean("favorite",false),x.optString("group","Imports"))}}
        val tracks=r.getJSONArray("tracks").let{a->require(a.length()==storedTracks);(0 until storedTracks).map{i->val t=a.getJSONObject(i);TrackControls(t.getDouble("gain").toFloat(),t.getDouble("pan").toFloat(),t.getBoolean("mute"),t.getBoolean("solo"),t.getBoolean("armed"),t.getDouble("sendA").toFloat(),t.getDouble("sendB").toFloat(),t.getString("name"))}}
        val patches=r.getJSONArray("patches").let{a->require(a.length()==storedTracks);(0 until storedTracks).map{requireNotNull(SynthPatch.decode(a.getString(it))){"Invalid synth patch."}}}
        val effects=r.getJSONArray("effects").let{a->require(a.length()==storedTracks);(0 until storedTracks).map{requireNotNull(FxConfig.decode(a.getString(it))){"Invalid effects rack."}}}
        val instruments=if(version==1)List(storedTracks){SampleInstrument()} else r.getJSONArray("instruments").let{a->
            require(a.length()==storedTracks);(0 until storedTracks).map{t->val x=a.getJSONArray(t);require(x.length()==290)
                fun zone(at: Int): SampleZone {
                    fun flag(i: Int): Boolean {val v=x.int(at+i);require(v in 0..1);return v==1}
                    return SampleZone(x.int(at),x.frame(at+1),x.frame(at+2),x.frame(at+3),x.frame(at+4),x.int(at+5),
                        x.getDouble(at+6).toFloat(),x.getDouble(at+7).toFloat(),x.getDouble(at+8).toFloat(),x.getDouble(at+9).toFloat(),
                        x.getDouble(at+10).toFloat(),x.getDouble(at+11).toFloat(),x.getDouble(at+12).toFloat(),flag(13),flag(14),flag(15),x.int(at+16))
                }
                SampleInstrument(x.int(0),zone(1),List(16){zone(18+it*17)})
            }
        }
        val tempo=if(r.int("version")<3)emptyList() else r.getJSONArray("tempoPoints").let{a->a.items(128).map{i->val x=a.getJSONArray(i);require(x.length()==3);val ramp=x.int(2);require(ramp in 0..1);TempoPoint(x.getDouble(0),x.getDouble(1).toFloat(),ramp==1)}}
        val tempoEnabled=r.int("version")>=3&&r.getBoolean("tempoEnabled")
        val frozen=if(version<7)emptyList() else r.getJSONArray("frozen").let{a->a.items(ProjectLimits.TRACKS).map{i->val f=a.getJSONArray(i);require(f.length()==4);FrozenTrack(f.int(0),f.int(1),f.frame(2),f.getString(3))}}
        val document=ProjectDocument(Project(r.getString("name"),signature.int(0),signature.int(1),r.int("mode"),r.int("quantization"),r.getBoolean("loop"),r.getDouble("loopStart"),r.getDouble("loopEnd"),clips,regions,assets,expanded(instruments){SampleInstrument()},tempo,tempoEnabled,frozen),
            r.getDouble("bpm").toFloat(),r.getDouble("master").toFloat(),r.getDouble("limiterDrive").toFloat(),r.getDouble("limiterRelease").toFloat(),expanded(tracks){TrackControls()},expanded(patches){SynthPatch.initial()},expanded(effects){FxConfig.initial()}).also{require(it.valid()){ "Invalid project values or overlapping arrangement regions." }}
        return FrozenTracks.reconcile(document)
    }
}
