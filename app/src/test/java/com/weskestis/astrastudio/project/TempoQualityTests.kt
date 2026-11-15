package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.audio.StudioState
import com.weskestis.astrastudio.audio.AudioStats
import com.weskestis.astrastudio.audio.editorSnapshot
import com.weskestis.astrastudio.audio.sessionSnapshot
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class TempoQualityTests {
    private val steps=listOf(TempoPoint(0.0,120f),TempoPoint(4.0,60f))
    private val ramp=steps.mapIndexed{i,p->p.copy(ramp=i==0)}
    private val asset=AudioAsset(0,"Tone","tone.wav",48000,480000,List(1024){0f})
    private fun d(p: Project=Project())=ProjectDocument(p,120f,.7f,0f,80f,List(16){TrackControls()},List(16){SynthPatch.initial()},List(16){FxConfig.initial()})
    private fun reject(block: ()->Unit)=assertTrue(runCatching(block).isFailure)
    @Test fun stepTempoCountsTimeAndInverts(){assertEquals(6.0,TempoMap.seconds(steps,0.0,8.0,145f),1e-12);assertEquals(8.0,TempoMap.advance(steps,0.0,6.0,145f),1e-12);assertEquals(-6.0,TempoMap.seconds(steps,8.0,0.0,145f),1e-12);assertEquals(120.0,TempoMap.bpmAt(steps,3.9,145f),0.0);assertEquals(60.0,TempoMap.bpmAt(steps,4.0,145f),0.0)}
    @Test fun rampIsLinearInBeatsWithLogarithmicTime(){assertEquals(90.0,TempoMap.bpmAt(ramp,2.0,145f),0.0);assertEquals(4*ln(2.0)+4,TempoMap.seconds(ramp,0.0,8.0,145f),1e-12);for(end in listOf(.1,2.0,4.0,9.3))assertEquals(end,TempoMap.advance(ramp,0.0,TempoMap.seconds(ramp,0.0,end,145f),145f),1e-12)}
    @Test fun fractionalRampsAndSeeksRoundTrip(){val nodes=listOf(TempoPoint(0.0,138.2f,true),TempoPoint(1.7,155.7f,true),TempoPoint(9.3,90.1f));for(start in listOf(0.0,.4,1.7,6.4))for(end in listOf(9.3,16.1))assertEquals(end,TempoMap.advance(nodes,start,TempoMap.seconds(nodes,start,end,128f),128f),1e-10)}
    @Test fun disabledMapUsesBaseTempo(){val p=Project(tempoPoints=ramp);assertEquals(4.0,p.timelineSeconds(0.0,8.0,120f),0.0);assertEquals(4*ln(2.0)+4,p.copy(tempoEnabled=true).timelineSeconds(0.0,8.0,120f),1e-12)}
    @Test fun invalidMapsRejected(){assertTrue(TempoMap.valid(emptyList(),false));assertFalse(TempoMap.valid(emptyList(),true));for(points in listOf(listOf(TempoPoint(1.0,120f)),listOf(TempoPoint(0.0,Float.NaN)),listOf(TempoPoint(0.0,29f)),steps+steps.last(),steps+TempoPoint(Double.POSITIVE_INFINITY,60f),List(129){TempoPoint(it.toDouble(),120f)}))assertFalse(TempoMap.valid(points,true));reject{TempoMap.advance(steps,0.0,-1.0,120f)};reject{TempoMap.seconds(steps,0.0,1.0,Float.NaN)}}
    @Test fun versionThreePersistsAndCompilesMap(){val doc=d(Project(tempoPoints=ramp,tempoEnabled=true));assertEquals(doc,ProjectCodec.decode(ProjectCodec.encode(doc)));assertTrue(doc.valid());val wire=doc.project.nativeWire();assertEquals(4.0,wire[0],0.0);assertArrayEquals(doubleArrayOf(1.0,2.0,0.0,120.0,1.0,4.0,60.0,0.0),wire.dropLast(1).takeLast(8).toDoubleArray(),0.0)}
    @Test fun earlierProjectVersionsMigrateWithoutTempoMap(){for(version in 1..2){val json=JSONObject(ProjectCodec.encode(d())).put("version",version);json.remove("tempoPoints");json.remove("tempoEnabled");val loaded=ProjectCodec.decode(json.toString());assertEquals(emptyList<TempoPoint>(),loaded.project.tempoPoints);assertFalse(loaded.project.tempoEnabled);assertTrue(loaded.valid())}}
    @Test fun malformedTempoFilesRejected(){val json=JSONObject(ProjectCodec.encode(d(Project(tempoPoints=steps,tempoEnabled=true))));json.getJSONArray("tempoPoints").getJSONArray(0).put(2,2);reject{ProjectCodec.decode(json.toString())};json.remove("tempoPoints");reject{ProjectCodec.decode(json.toString())}}
    @Test fun splitAudioUsesTempoMapAndExactSourceFrame(){val p=Project(assets=listOf(asset),clips=listOf(Clip(0,length=8.0,asset=0,last=asset.frames)),tempoPoints=ramp,tempoEnabled=true).place(0,0.0);val split=p.splitRegion(1,4.123456,120f);val frame=split.regions[1].audioOffset;assertEquals(kotlin.math.round(p.timelineSeconds(0.0,4.123456,120f)*48000).toLong(),frame);assertEquals(frame.toDouble()/48000,p.timelineSeconds(0.0,split.regions[1].beat,120f),1e-12);assertEquals(8.0,split.regions.sumOf{it.length},1e-12)}
    @Test fun isolatedResamplingUsesBaseTempoWhileArrangementKeepsMap(){val doc=d(Project(clips=listOf(Clip(0,length=8.0)),tempoPoints=ramp,tempoEnabled=true).place(0,0.0));val isolated=Resampling.source(doc,0,false);assertFalse(isolated.project.tempoEnabled);assertEquals(4.0,isolated.project.timelineSeconds(0.0,8.0,120f),0.0);assertEquals(doc,Resampling.source(doc,0,true))}
    @Test fun stretchDurationAndPitchHaveSeparateControls(){val c=Clip(0,length=20.0,asset=0,last=asset.frames);val p=Project(assets=listOf(asset),clips=listOf(c));assertEquals(480000L,StretchSettings.outputFrames(p,c,120f,20.0,0.0));assertEquals(600000L,StretchSettings.outputFrames(p,c,120f,25.0,7.0));assertEquals(600000L,StretchSettings.outputFrames(p,c,120f,25.0,-12.0));reject{StretchSettings.outputFrames(p,c,120f,41.0,0.0)};reject{StretchSettings.outputFrames(p,c,120f,9.0,0.0)};reject{StretchSettings.outputFrames(p,c,120f,20.0,25.0)};reject{StretchSettings.outputFrames(p,c,120f,20.0,Double.NaN)}}
    @Test fun stretchBudgetsAccountForRetainedOriginal(){val c=Clip(0,asset=0,last=asset.frames);val p=Project(assets=listOf(asset,asset.copy(id=1,file="large.wav",frames=67108864-asset.frames)),clips=listOf(c));reject{StretchSettings.outputFrames(p,c,120f,20.0,0.0)};reject{StretchSettings.outputFrames(p,Clip(1),120f,20.0,0.0)}}
    @Test fun tempoEditsUndoInOneStep(){val before=d();val after=before.copy(project=before.project.copy(tempoPoints=ramp,tempoEnabled=true));val history=ProjectHistory();history.commit(before,after);assertEquals(before,history.undo(after));assertEquals(after,history.redo(before))}
    @Test fun meterPollingDoesNotInvalidateStaticEditors(){val a=StudioState(stats=AudioStats(running=true,frame=10,cpuLoad=.1));val b=a.copy(stats=a.stats.copy(frame=2048,cpuLoad=.2,peakLeft=.8f));assertEquals(a.editorSnapshot(),b.editorSnapshot());assertEquals(a.sessionSnapshot(),b.sessionSnapshot());assertNotEquals(a.editorSnapshot(),a.copy(stats=AudioStats()).editorSnapshot());assertNotEquals(a.editorSnapshot(),a.copy(project=a.project.copy(tempoPoints=ramp)).editorSnapshot())}
    @Test fun sessionSeparatesDrawProgressFromStructuralEdits(){val a=StudioState();val b=a.copy(sequence=a.sequence.copy(phase=listOf(.5f)+List(15){0f}));assertEquals(a.sessionSnapshot(),b.sessionSnapshot());assertEquals(a.editorSnapshot(),b.editorSnapshot());assertNotEquals(a.sessionSnapshot(),a.copy(sequence=a.sequence.copy(active=listOf(0)+List(15){-1})).sessionSnapshot());assertNotEquals(a.sessionSnapshot(),a.copy(project=a.project.put(Clip(0))).sessionSnapshot())}
}
