package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReliabilityTests {
    private fun doc(name: String="Test",project: Project=Project(name=name,clips=listOf(Clip(0,length=2.0).add(0.0,48,1.0))).place(0,0.0))=
        ProjectDocument(project,120f,.7f,0f,80f,List(50){TrackControls()},List(50){SynthPatch.initial()},List(50){FxConfig.initial()})
    private fun frozen(base: ProjectDocument=doc()): ProjectDocument {
        val a=AudioAsset(0,"Frozen","freeze.wav",192000,384000,List(1024){0f},group="Frozen tracks")
        return base.copy(project=base.project.copy(assets=base.project.assets+a,frozen=listOf(FrozenTrack(0,0,FrozenTracks.musicFrames(base,192000),FrozenTracks.fingerprint(base,0)))))
    }
    private fun rejects(block: ()->Unit){assertTrue(runCatching(block).isFailure)}
    private fun journal(test: (RecoveryJournal,File)->Unit){val dir=Files.createTempDirectory("astra-recovery-").toFile();try{test(RecoveryJournal(dir),dir)}finally{dir.deleteRecursively()}}
    @Test fun freezeRoundTripKeepsOriginalsAndNativeTrailer(){val d=frozen();assertTrue(d.valid());assertEquals(d,ProjectCodec.decode(ProjectCodec.encode(d)));assertEquals(doc().project.clips,d.project.clips);assertEquals(doc().project.regions,d.project.regions)
        assertArrayEquals(doubleArrayOf(1.0,0.0,0.0,192000.0),d.project.nativeWire().dropLast(ProjectLimits.TRACKS*70).takeLast(4).toDoubleArray(),0.0)}
    @Test fun phaseElevenProjectsRemainFiftyTracks(){val d=doc(project=Project(clips=listOf(Clip(399).add(0.0,48,.5))).place(399,0.0));val json=JSONObject(ProjectCodec.encode(d)).put("version",6);json.remove("frozen");assertEquals(d,ProjectCodec.decode(json.toString()))}
    @Test fun fingerprintSurvivesPortableFilenamesAndMetadata(){val base=frozen();val changed=base.copy(project=base.project.copy(name="Renamed",mode=1,loop=true,assets=base.project.assets.map{it.copy(file="imported.wav",name="Cache",favorite=true,group="Mine")}));assertEquals(base.project.frozen,FrozenTracks.reconcile(changed).project.frozen)}
    @Test fun sourceNoteEditUnfreezesAndReclaimsUnusedSlot(){val d=frozen();val edited=d.copy(project=d.project.put(d.project.clip(0)!!.editNote(1,pitch=36)));val next=FrozenTracks.reconcile(edited,true);assertTrue(next.project.frozen.isEmpty());assertTrue(next.project.assets.isEmpty());assertEquals(36,next.project.clip(0)!!.notes[0].pitch);assertTrue(next.valid())}
    @Test fun codecRetainsArchiveAssetInventoryWhenDiscardingStaleCache(){val d=frozen();val edited=d.copy(bpm=138f);val next=ProjectCodec.decode(ProjectCodec.encode(edited));assertTrue(next.project.frozen.isEmpty());assertEquals(d.project.assets,next.project.assets)}
    @Test fun patchAndInstrumentAndSourceAutomationInvalidateFreeze(){val d=frozen();val patch=d.patches.toMutableList().also{it[0]=it[0].shape(0,3)}
        assertTrue(FrozenTracks.reconcile(d.copy(patches=patch)).project.frozen.isEmpty())
        assertTrue(FrozenTracks.reconcile(d.copy(project=d.project.instrument(0){it.copy(mode=1)})).project.frozen.isEmpty())
        assertTrue(FrozenTracks.reconcile(d.copy(project=d.project.put(d.project.clip(0)!!.point(2,0.0,400f,.25)))).project.frozen.isEmpty())}
    @Test fun tempoAndArrangementLengthInvalidateCache(){val d=frozen();val altered=listOf(d.copy(bpm=138f),d.copy(project=d.project.copy(tempoEnabled=true,tempoPoints=listOf(TempoPoint(0.0,120f),TempoPoint(1.0,90f)))),d.copy(project=d.project.copy(regions=d.project.regions.map{it.copy(beat=1.0)})))
        altered.forEach{assertTrue(FrozenTracks.reconcile(it).project.frozen.isEmpty())}}
    @Test fun mixerEffectsAndPostSourceAutomationRemainLive(){val d=frozen();val next=d.copy(master=.5f,limiterDrive=3f,tracks=d.tracks.map{it.copy(gain=.5f,pan=.4f,sendA=.2f)},effects=d.effects.map{it.copy(name="New effect name")},project=d.project.put(d.project.clip(0)!!.point(0,0.0,.7f,.25).point(1,0.0,.3f,.25).point(3,0.0,.4f,.25)))
        assertEquals(d.project.frozen,FrozenTracks.reconcile(next).project.frozen)}
    @Test fun otherTrackSourceChangesKeepFreeze(){val d=frozen();val next=d.copy(project=d.project.put(Clip(1,length=2.0).add(0.0,36,.5)).place(1,0.0));assertEquals(d.project.frozen,FrozenTracks.reconcile(next).project.frozen)}
    @Test fun unfreezeKeepsCacheUsedAsAnotherClip(){val d=frozen();val used=d.copy(project=d.project.put(Clip(1,length=2.0,asset=0,last=384000)));val next=FrozenTracks.unfreeze(used,0);assertTrue(next.project.frozen.isEmpty());assertEquals(d.project.assets,next.project.assets);assertTrue(next.valid())}
    @Test fun repeatedFreezeUnfreezeDoesNotLeakAssetSlots(){repeat(100){val d=FrozenTracks.unfreeze(frozen(),0);assertTrue(d.project.assets.isEmpty());FrozenTracks.check(d,0,192000,10f)}}
    @Test fun undoRestoresCompleteFreezeDocument(){val h=ProjectHistory();val a=frozen();val b=FrozenTracks.unfreeze(a,0);h.commit(a,b);assertEquals(a,h.undo(b));assertEquals(b,h.redo(a))}
    @Test fun freezeBoundsRejectMissingAssetsTracksFramesAndDuplicates(){val d=frozen();val f=d.project.frozen.single();for(bad in listOf(f.copy(track=50),f.copy(asset=63),f.copy(musicFrames=0),f.copy(musicFrames=384001),f.copy(fingerprint="bad"))){assertFalse(d.copy(project=d.project.copy(frozen=listOf(bad))).valid())};assertFalse(d.copy(project=d.project.copy(frozen=listOf(f,f))).valid());assertFalse(d.copy(project=d.project.copy(frozen=listOf(f,f.copy(track=1)))).valid())}
    @Test fun freezeRequiresArrangementAndHonorsSourceBudget(){rejects{FrozenTracks.check(doc(project=Project()),0,192000,10f)};rejects{FrozenTracks.check(frozen(),0,192000,10f)};rejects{FrozenTracks.check(doc(),50,192000,10f)};rejects{FrozenTracks.check(doc(),0,44100,10f)};rejects{FrozenTracks.check(doc(),0,192000,Float.NaN)};val large=doc(project=Project(clips=listOf(Clip(0,length=65536.0))).place(0,0.0));rejects{FrozenTracks.check(large,0,192000,0f)}}
    @Test fun freezeFrameEstimatesMatchFloatNativeTailAndTempoMap(){for(rate in FrozenTracks.rates){assertEquals(rate.toLong()+kotlin.math.ceil((.1234567f*rate).toDouble()).toLong(),FrozenTracks.frames(doc(),rate,.1234567f))};val d=doc(project=Project(clips=listOf(Clip(0,length=8.0)),tempoEnabled=true,tempoPoints=listOf(TempoPoint(0.0,120f),TempoPoint(4.0,60f))).place(0,0.0));assertEquals(1152000L,FrozenTracks.musicFrames(d,192000))}
    @Test fun recoveryNewestThreeAndCurrentSurviveFreshInstance(){journal{j,dir->for(n in 1..5)j.save(doc("Save $n"));assertEquals("Save 5",RecoveryJournal(dir).load()!!.project.name);assertEquals(listOf("Save 4","Save 3","Save 2"),j.entries().map{it.name});j.entries().forEach{assertEquals(it.name,j.restore(it.token).project.name)}}}
    @Test fun identicalAutosaveDoesNotRotateHistory(){journal{j,_->j.save(doc("A"));j.save(doc("B"));val entries=j.entries();j.save(doc("B"));assertEquals(entries,j.entries())}}
    @Test fun recoveryPreservesOriginalSaveAge(){journal{j,dir->j.save(doc("A"));assertTrue(File(dir,"current.astra.json").setLastModified(1234567890000));j.save(doc("B"));j.save(doc("C"));assertEquals(1234567890000,j.entries().first{it.name=="A"}.savedAt)}}
    @Test fun corruptCurrentFallsBackAndPreservesDamagedBytes(){journal{j,dir->j.save(doc("A"));j.save(doc("B"));File(dir,"current.astra.json").writeText("broken");assertEquals("A",j.load()!!.project.name);assertTrue(j.notice!!.contains("damaged copy was preserved"));assertEquals("broken",dir.listFiles()!!.single{it.name.startsWith("damaged-")}.readText());j.save(doc("Recovered"));assertEquals("Recovered",j.load()!!.project.name)}}
    @Test fun unreadableCurrentAudioFallsBackToUsableSource(){journal{j,_->j.save(doc("Readable"));j.save(doc("Missing audio"));val d=j.load{check(it.project.name!="Missing audio")};assertEquals("Readable",d!!.project.name)}}
    @Test fun interruptedPendingSaveNeverReplacesValidCurrent(){journal{j,dir->j.save(doc("A"));File(dir,"current.astra.json.pending").writeText("partial");assertEquals("A",RecoveryJournal(dir).load()!!.project.name);j.save(doc("B"));assertFalse(File(dir,"current.astra.json.pending").exists());assertEquals("B",j.load()!!.project.name)}}
    @Test fun corruptNewestBackupSkipsToNextValidGeneration(){journal{j,dir->for(n in 1..4)j.save(doc("Save $n"));File(dir,"current.astra.json").writeText("broken");File(dir,"previous-1.astra.json").writeText("broken");assertEquals("Save 2",j.load()!!.project.name)}}
    @Test fun missingCurrentRecoveryDoesNotClaimDamagePreserved(){journal{j,dir->j.save(doc("A"));j.save(doc("B"));File(dir,"current.astra.json").delete();assertEquals("A",j.load()!!.project.name);assertFalse(j.notice!!.contains("damaged"))}}
    @Test fun noUsableRecoveryFailsWithoutInventingProject(){journal{j,dir->assertNull(j.load());j.save(doc());File(dir,"current.astra.json").writeText("bad");rejects{j.load()};assertTrue(dir.listFiles()!!.any{it.name.startsWith("damaged-")})}}
    @Test fun expiredRecoverySelectionCannotRestoreWrongCopy(){journal{j,_->j.save(doc("A"));j.save(doc("B"));val token=j.entries().single().token;for(n in 1..4)j.save(doc("New $n"));rejects{j.restore(token)}}}
    @Test fun recoveryRefusesInvalidDocumentsWithoutChangingCurrent(){journal{j,_->j.save(doc("A"));rejects{j.save(doc("Invalid").copy(bpm=Float.NaN))};assertEquals("A",j.load()!!.project.name)}}
    @Test fun recoveryHistoryCanUndoAndRedoRestore(){journal{j,_->val h=ProjectHistory();val a=doc("A");val b=doc("B");j.save(a);j.save(b);val restored=j.restore(j.entries().single().token);h.commit(b,restored);assertEquals(b,h.undo(restored));assertEquals(a,h.redo(b))}}
    @Test fun observedXrunsSurviveStreamResetAndUnknownReadings(){val meter=PerformanceMeter();fun sample(x: Int)=meter.sample(AudioStats(running=true,xruns=x),1000,0,0)
        assertEquals(0L,sample(10).xruns);assertEquals(3L,sample(13).xruns);assertEquals(3L,sample(-1).xruns);assertEquals(4L,sample(14).xruns);assertEquals(6L,sample(2).xruns)}
    @Test fun performanceTracksPolledPeaksAndCanReset(){val meter=PerformanceMeter();meter.sample(AudioStats(running=true,cpuLoad=.7,activeVoices=50,xruns=0),0,100,200);val p=meter.sample(AudioStats(running=true,cpuLoad=.3,activeVoices=3,xruns=2),3500,300,400);assertEquals(.7,p.peakCpu,0.0);assertEquals(50,p.peakVoices);assertEquals(2L,p.polls);assertEquals(3L,p.elapsedSeconds);assertEquals(2L,p.xruns);assertEquals(400L,p.nativeBytes);meter.reset();assertEquals(0L,meter.sample(AudioStats(),4000,0,0).polls)}
    @Test fun metricsRejectNonfiniteLoadAndDoNotInvalidateEditors(){val meter=PerformanceMeter();val p=meter.sample(AudioStats(running=true,cpuLoad=Double.NaN),1000,-1,-1);assertEquals(0.0,p.peakCpu,0.0);assertEquals(0L,p.javaBytes);val a=StudioState();assertEquals(a.editorSnapshot(),a.copy(performance=p).editorSnapshot());assertEquals(a.sessionSnapshot(),a.copy(performance=p).sessionSnapshot())}
}
