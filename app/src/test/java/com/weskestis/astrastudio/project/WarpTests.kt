package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WarpTests {
    private val asset=AudioAsset(0,"Original","original.wav",48000,240000,List(1024){0f})
    private val clip=Clip(0,length=8.0,asset=0,first=24000,last=216000)
    private val project=Project(assets=listOf(asset),clips=listOf(clip))
    private fun doc(p: Project)=ProjectDocument(p,145f,.7f,0f,80f,List(ProjectLimits.TRACKS){TrackControls()},List(ProjectLimits.TRACKS){SynthPatch.initial()},List(ProjectLimits.TRACKS){FxConfig.initial()})
    private fun reject(f: ()->Unit)=assertTrue(runCatching(f).isFailure)
    @Test fun settingsPreserveOriginalAndLength(){val p=WarpSettings.apply(project,0,true,120.0,48000,7.0);val c=p.clip(0)!!;assertTrue(p.valid());assertEquals(project.assets,p.assets);assertEquals(clip.first,c.first);assertEquals(clip.last,c.last);assertEquals(clip.length,c.length,0.0);assertEquals(24000L,c.warpStart);assertEquals(96000.0,WarpSettings.sourceFrame(c,asset,2.0),1e-8)}
    @Test fun versionFourRoundTripsAllWarpValues(){val d=doc(WarpSettings.apply(project,0,true,138.25,48000,-7.5));assertEquals(d,ProjectCodec.decode(ProjectCodec.encode(d)));val w=d.project.nativeWire();assertEquals(6.0,w[0],0.0);assertArrayEquals(doubleArrayOf(1.0,0.0,1.0,138.25,24000.0,-7.5),w.dropLast(1).takeLast(6).toDoubleArray(),0.0)}
    @Test fun oldVersionsDefaultToOriginalSpeed(){for(v in 1..3){val j=legacyJson(doc(project),v);val c=j.getJSONArray("clips").getJSONObject(0);listOf("warp","sourceBpm","warpStart","pitch").forEach{c.remove(it)};val old=ProjectCodec.decode(j.toString());assertEquals(clip,old.project.clip(0));assertFalse(old.project.clip(0)!!.warp)}}
    @Test fun invalidValuesRejectedBeforeEngine(){for(bpm in listOf(29.0,301.0,Double.NaN,Double.POSITIVE_INFINITY))reject{WarpSettings.apply(project,0,true,bpm,24000,0.0)};for(frame in listOf(-1L,23999L,216000L))reject{WarpSettings.apply(project,0,true,120.0,frame,0.0)};for(pitch in listOf(-25.0,25.0,Double.NaN))reject{WarpSettings.apply(project,0,true,120.0,24000,pitch)};reject{WarpSettings.apply(Project(clips=listOf(Clip(0))),0,true,120.0,0,0.0)}}
    @Test fun malformedSavedWarpIsRejected(){val j=JSONObject(ProjectCodec.encode(doc(project)));j.getJSONArray("clips").getJSONObject(0).put("warpStart",192000);reject{ProjectCodec.decode(j.toString())};assertFalse(Project(clips=listOf(Clip(0,warp=true))).valid())}
    @Test fun undoRestoresAllSettingsWithoutReplacingMedia(){val old=doc(project);val next=old.copy(project=WarpSettings.apply(project,0,true,90.0,48000,12.0));val h=ProjectHistory();h.commit(old,next);assertEquals(old,h.undo(next));assertEquals(next,h.redo(old));assertEquals(old.project.assets,next.project.assets)}
    @Test fun fitUsesSelectedDownbeatAndMusicalLength(){assertEquals(120.0,WarpSettings.fittedTempo(clip,asset,24000),0.0);assertEquals(240.0,WarpSettings.fittedTempo(clip,asset,120000),0.0)}
    @Test fun splitRetainsMusicalSourcePositionAcrossTempoMap(){val p=WarpSettings.apply(project,0,true,120.0,24000,0.0).copy(tempoEnabled=true,tempoPoints=listOf(TempoPoint(0.0,145f,true),TempoPoint(4.0,90f))).place(0,0.0);val cut=p.splitRegion(1,3.25,145f);val right=cut.regions.last();assertEquals(3.25,right.offset,0.0);assertEquals(0L,right.audioOffset);assertEquals(102000.0,WarpSettings.sourceFrame(cut.clip(0)!!,asset,right.offset,right.audioOffset),0.0);assertEquals(8.0,cut.regions.sumOf{it.length},0.0);assertEquals(p.assets,cut.assets)}
    @Test fun disablingKeepsEditableSettings(){val p=WarpSettings.apply(project,0,true,138.0,48000,-12.0);val off=WarpSettings.apply(p,0,false,138.0,48000,-12.0);assertFalse(off.clip(0)!!.warp);assertEquals(p.clip(0)!!.copy(warp=false),off.clip(0))}
    @Test fun trimmingRetainsOrClampsDownbeatAndUsesSourceTempo(){val c=WarpSettings.apply(project,0,true,120.0,48000,7.0).clip(0)!!;val trim=WarpSettings.trim(c,36000,144000,asset,145f);assertEquals(12000L,trim.warpStart);assertEquals(4.0,trim.length,0.0);assertEquals(7.0,trim.pitch,0.0);assertTrue(project.put(trim).valid());val cut=WarpSettings.trim(c,72000,144000,asset,145f);assertEquals(0L,cut.warpStart);assertEquals(3.0,cut.length,0.0)}
    @Test fun duplicatesAndResamplingCarryWarpSettings(){val p=WarpSettings.apply(project,0,true,120.0,24000,7.0);val next=p.duplicate(0,ProjectLimits.TRACKS);assertEquals(7.0,next.clip(ProjectLimits.TRACKS)!!.pitch,0.0);assertTrue(next.clip(ProjectLimits.TRACKS)!!.warp);assertEquals(p.clip(0),Resampling.source(doc(p),0,false).project.clip(0))}
}
