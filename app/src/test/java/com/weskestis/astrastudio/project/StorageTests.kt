package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StorageTests {
    private fun asset(id: Int,frames: Long)=AudioAsset(id,"Long take","take$id.wav",48000,frames,List(1024){0f})
    private fun doc(p: Project)=ProjectDocument(p,120f,.7f,0f,80f,List(ProjectLimits.TRACKS){TrackControls()},List(ProjectLimits.TRACKS){SynthPatch.initial()},List(ProjectLimits.TRACKS){FxConfig.initial()})
    private fun rejects(f: ()->Unit)=assertTrue(runCatching(f).isFailure)
    @Test fun largeSourceAndFrameOffsetsSurviveProjectRoundTrip(){val a=asset(0,100000000);val p=Project(assets=listOf(a),clips=listOf(Clip(0,asset=0,first=70000000,last=71000000)))
        assertTrue(p.valid());val d=doc(p);val json=ProjectCodec.encode(d);assertEquals(7,JSONObject(json).getInt("version"));assertEquals(d,ProjectCodec.decode(json));assertEquals(70000000.0,p.nativeWire()[12],0.0)}
    @Test fun oldProjectFormatsRetainMetadata(){val d=doc(Project(assets=listOf(asset(0,48000))));for(version in 1..4){val json=legacyJson(d,version);assertEquals(d,ProjectCodec.decode(json.toString()))}}
    @Test fun perSourceAndTotalCapacityAreIndependent(){val full=Project(assets=List(4){asset(it,AudioStorage.MAX_ASSET_FRAMES)});assertTrue(full.valid());assertFalse(full.copy(assets=full.assets+asset(4,1)).valid());assertFalse(Project(assets=listOf(asset(0,AudioStorage.MAX_ASSET_FRAMES+1))).valid());assertFalse(AudioStorage.canAdd(full,1));assertTrue(AudioStorage.canAdd(Project(),100000000))}
    @Test fun sampleSlotsStillHaveAnExplicitBound(){val full=Project(assets=List(64){asset(it,1)});assertTrue(full.valid());assertFalse(AudioStorage.canAdd(full,1));assertFalse(full.copy(assets=full.assets+asset(64,1)).valid())}
    @Test fun renderCapacityReservesWaveHeader(){assertTrue(AudioStorage.canAdd(Project(),AudioStorage.MAX_RENDER_FRAMES));assertFalse(AudioStorage.canAdd(Project(),AudioStorage.MAX_RENDER_FRAMES+1));assertFalse(AudioStorage.canAdd(Project(),Long.MAX_VALUE));assertFalse(AudioStorage.canAdd(Project(),0))}
    @Test fun exportNamesNeverEscapeTheExportDirectory(){for(name in listOf("../../mix","a/b\\c","\ntrack\u0000","🔥","", "a".repeat(500))){val stem=ExportNames.stem(name);assertTrue(stem.isNotBlank()&&stem.length<=64);assertFalse(stem.contains('/')||stem.contains('\\')||stem.contains('.')||stem.any{it.isISOControl()})}}
    @Test fun exportByteEstimateIncludesTailRateAndBitDepth(){val p=Project(clips=listOf(Clip(0,length=4.0))).place(0,0.0);assertEquals(44+144000L*6,ExportNames.bytes(p,120f,48000,1f,false));assertEquals(44+144000L*8,ExportNames.bytes(p,120f,48000,1f,true));assertEquals(44+88200L*6,ExportNames.bytes(p,120f,44100,0f,false));rejects{ExportNames.bytes(Project(),120f,48000,0f,false)};rejects{ExportNames.bytes(p,120f,12345,0f,false)};rejects{ExportNames.bytes(p,120f,48000,Float.NaN,false)}}
    @Test fun tempoMapUsedForFinishedTrackLength(){val p=Project(clips=listOf(Clip(0,length=8.0)),tempoEnabled=true,tempoPoints=listOf(TempoPoint(0.0,120f),TempoPoint(4.0,60f))).place(0,0.0);assertEquals(44+288000L*6,ExportNames.bytes(p,145f,48000,0f,false))}
    @Test fun master192HasSixByteStereoFrames(){val p=Project(clips=listOf(Clip(0,length=4.0))).place(0,0.0);assertEquals(44+576000L*6,ExportNames.bytes(p,120f,192000,1f,false));assertEquals(69120000L,ExportFormats.bytesPerMinute(192000,false))}
    @Test fun everyOfferedRateHasAnExactSizeEstimate(){val p=Project(clips=listOf(Clip(0,length=2.0))).place(0,0.0);for(rate in ExportFormats.sampleRates){assertEquals(44+rate.toLong()*6,ExportNames.bytes(p,120f,rate,0f,false));assertEquals(44+rate.toLong()*8,ExportNames.bytes(p,120f,rate,0f,true))}}
    @Test fun highRateWaveOverflowRejectedBeforeRendering(){val p=Project(clips=listOf(Clip(0,length=8192.0))).place(0,0.0);assertTrue(ExportNames.bytes(p,120f,48000,0f,false)>0);rejects{ExportNames.bytes(p,120f,192000,0f,false)};rejects{ExportNames.bytes(p,120f,192000,0f,true)}}
    @Test fun fractionalTailRoundsUpAtTheSelectedRate(){val p=Project(clips=listOf(Clip(0,length=2.0))).place(0,0.0);assertEquals(44+215704L*6,ExportNames.bytes(p,120f,192000,.1234567f,false));assertEquals(44+211200L*6,ExportNames.bytes(p,120f,192000,.1f,false))}
    @Test fun highRateTempoMapUsesMusicalDuration(){val p=Project(clips=listOf(Clip(0,length=8.0)),tempoEnabled=true,tempoPoints=listOf(TempoPoint(0.0,120f),TempoPoint(4.0,60f))).place(0,0.0);assertEquals(44+1152000L*6,ExportNames.bytes(p,145f,192000,0f,false))}
    @Test fun formatLabelsAndStorageExplainTheSelectedFormat(){assertEquals("192 kHz",ExportFormats.label(192000));assertEquals("44.1 kHz",ExportFormats.label(44100));assertEquals("176.4 kHz",ExportFormats.label(176400));assertEquals(92160000L,ExportFormats.bytesPerMinute(192000,true));rejects{ExportFormats.label(12345)}}
}
