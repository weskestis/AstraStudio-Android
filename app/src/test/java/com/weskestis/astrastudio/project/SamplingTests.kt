package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.recording.FloatWaveWriter
import com.weskestis.astrastudio.recording.WaveFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class SamplingTests {
    @get:Rule val temp=TemporaryFolder()
    private val cancel=AtomicBoolean()
    private fun doc(p: Project=Project())=ProjectDocument(p,120f,.7f,0f,80f,List(50){TrackControls()},List(50){SynthPatch.initial()},List(50){FxConfig.initial()})
    private fun wav(frames: Int=48000,signal: (Int)->Float={.5f*sin(it*.01f)}): File = temp.newFile("${System.nanoTime()}.wav").also{file->FloatWaveWriter(file,48000,2).use{w->val b=FloatArray(frames*2){signal(it/2)};w.write(b,b.size)}}
    private fun asset(f: File,id: Int=0)=WaveFile(f).use{AudioAsset(id,"Test",f.name,it.rate,it.frames,List(1024){0f})}
    private fun layered(a: AudioAsset)=SampleInstrument(mode=2).edit(0){it.load(a).copy(sustain=1f)}.addLayer(0).editLayer(0,1){it.load(a).copy(pitch=12f,gain=.3f,fadeIn=24,fadeOut=48,crossfade=100,loop=true,oneShot=false)}
    private fun reject(block: ()->Unit){assertTrue(runCatching(block).isFailure)}
    @Test fun independentFourLayerEditing(){val a=asset(wav());var i=layered(a).addLayer(0).addLayer(0);reject{i.addLayer(0)};i=i.editLayer(0,2){it.load(a).copy(pitch=-12f)};assertEquals(0f,i.zone(0).pitch,0f);assertEquals(12f,i.zone(0,1).pitch,0f);assertEquals(-12f,i.zone(0,2).pitch,0f);assertTrue(i.valid(listOf(a)))}
    @Test fun clearingBasePreservesExtraLayers(){val a=asset(wav());val i=layered(a).removeLayer(0,0);assertEquals(-1,i.zone(0).asset);assertEquals(0,i.zone(0,1).asset);assertEquals(setOf(0),i.assets());assertTrue(i.valid(listOf(a)))}
    @Test fun removingExtraOnlyRemovesThatLayer(){val a=asset(wav());val i=layered(a).addLayer(0).editLayer(0,2){it.load(a).copy(pitch=-7f)}.removeLayer(0,1);assertEquals(2,i.zones(0).size);assertEquals(-7f,i.zone(0,1).pitch,0f)}
    @Test fun remapIncludesExtraOnlyAssets(){val a=asset(wav());val i=layered(a).removeLayer(0,0).mapAssets(mapOf(0 to 52));assertEquals(52,i.zone(0,1).asset);assertEquals(-1,i.zone(-1).asset)}
    @Test fun jsonAndWireRoundTripTrackFifty(){val a=asset(wav()).copy(slices=listOf(0,12000,48000));val d=doc(Project(assets=listOf(a)).instrument(49){layered(a)});assertEquals(d,ProjectCodec.decode(ProjectCodec.encode(d)));val w=d.project.nativeWire();assertEquals(8.0,w[0],0.0);assertTrue(w.size<ProjectLimits.NATIVE_VALUES)}
    @Test fun migratesPhaseThirteenWithoutExtensions(){val a=asset(wav());val d=doc(Project(assets=listOf(a)).instrument(0){it.copy(mode=1,sample=SampleZone().load(a))});val json=JSONObject(ProjectCodec.encode(d));json.put("version",8);json.remove("sampling");json.getJSONArray("assets").getJSONObject(0).remove("slices");assertEquals(d,ProjectCodec.decode(json.toString()))}
    @Test fun rejectsMalformedLayerCount(){val json=JSONObject(ProjectCodec.encode(doc()));json.getJSONArray("sampling").getJSONArray(0).put(3,4);reject{ProjectCodec.decode(json.toString())}}
    @Test fun rejectsFractionalFadeFrames(){val a=asset(wav());val json=JSONObject(ProjectCodec.encode(doc(Project(assets=listOf(a)).instrument(0){it.copy(sample=SampleZone().load(a))})) );json.getJSONArray("sampling").getJSONArray(0).put(0,.5);reject{ProjectCodec.decode(json.toString())}}
    @Test fun rangeClampsFadesAndOverlap(){val a=asset(wav());val z=SampleZone().load(a).copy(fadeIn=1000,fadeOut=900,crossfade=500).range(100,200,120,180);assertEquals(100L,z.fadeIn);assertEquals(100L,z.fadeOut);assertEquals(30L,z.crossfade);assertTrue(z.valid(listOf(a)));assertFalse(z.copy(crossfade=31).valid(listOf(a)))}
    @Test fun layerAndFadeInvalidateFrozenFingerprint(){val a=asset(wav());val d=doc(Project(assets=listOf(a)).instrument(0){it.copy(mode=1,sample=SampleZone().load(a))});val hash=FrozenTracks.fingerprint(d,0);assertNotEquals(hash,FrozenTracks.fingerprint(d.copy(project=d.project.instrument(0){it.edit(-1){z->z.copy(fadeIn=1)}}),0));assertNotEquals(hash,FrozenTracks.fingerprint(d.copy(project=d.project.instrument(0){it.addLayer(-1)}),0))}
    @Test fun equalMarkersPreserveEveryFrame(){val m=Slicing.equal(1001,16);assertEquals(17,m.size);assertEquals(1001L,m.zipWithNext().sumOf{it.second-it.first});assertTrue(Slicing.valid(m,1001));assertEquals(listOf(0L,1,2),Slicing.equal(2,16))}
    @Test fun manualMarkersCannotCrossOrDuplicate(){val m=Slicing.add(listOf(0,100),100,50);assertEquals(listOf(0L,50,100),m);assertEquals(listOf(0L,40,100),Slicing.move(m,1,40));assertEquals(listOf(0L,100),Slicing.remove(m,1));reject{Slicing.add(m,100,50)};reject{Slicing.move(m,1,100)};reject{Slicing.remove(m,0)}}
    @Test fun sixteenSliceBoundEnforced(){val m=Slicing.equal(1000,16);reject{Slicing.add(m,1000,1)};assertFalse(Slicing.valid(listOf(0,20,10,1000),1000));assertFalse(Slicing.valid(listOf(1,1000),1000))}
    @Test fun detectsQuietAndLoudTransientOnsets(){for(scale in listOf(1f,.001f)){val f=wav(48000){i->if(i%12000 in 0..127)scale*(1-i%12000/128f)else 0f};val result=Slicing.detect(f,16,.8f,cancel);assertEquals(5,result.size);for(i in 1..3)assertTrue(abs(result[i]-i*12000)<150)}}
    @Test fun silenceProducesOneSlice(){assertEquals(listOf(0L,48000),Slicing.detect(wav{0f},16,1f,cancel))}
    @Test fun detectorCancellationLeavesSourceUntouched(){val f=wav();val before=f.readBytes();reject{Slicing.detect(f,16,.8f,AtomicBoolean(true))};assertArrayEquals(before,f.readBytes())}
    @Test fun zeroCrossingConsidersBothChannels(){val f=wav(1000){i->sin((i-500)*PI/100).toFloat()};WaveFile(f).use{assertEquals(500L,Slicing.zero(it,510,30))}}
    @Test fun snapHandlesSingleFrameAndSmallLoops(){val f=wav(1){.5f};val a=asset(f);val z=Slicing.snap(f,SampleZone().load(a),cancel);assertTrue(z.valid(listOf(a)));assertEquals(0L,z.first);assertEquals(1L,z.last)}
    @Test fun slicingKitSharesSourceAndPreservesKeyboard(){val a=asset(wav()).copy(slices=Slicing.equal(48000,16));val old=layered(a).copy(sample=SampleZone().load(a));val i=Slicing.instrument(a,old);assertEquals(16,i.pads.count{it.asset==0});assertEquals(old.sample,i.sample);assertTrue(i.layers.drop(1).all{it.isEmpty()});assertEquals(48000L,i.pads.sumOf{it.last-it.first});assertTrue(i.valid(listOf(a)))}
    @Test fun generatedMidiReconstructsOriginalTiming(){val a=asset(wav()).copy(slices=listOf(0,6000,24000,48000));val c=Slicing.clip(a,49,120f);assertEquals(2.0,c.length,0.0);assertEquals(listOf(36,37,38),c.notes.map{it.pitch});assertEquals(.25,c.notes[1].beat,0.0);assertEquals(1.0,c.notes[2].length,0.0);assertTrue(Project(clips=listOf(c)).valid())}
    @Test fun tinySlicesCanPlayButCannotCreateInvalidMidi(){val a=asset(wav(10)).copy(slices=listOf(0,1,10));assertTrue(Slicing.instrument(a,SampleInstrument()).valid(listOf(a)));reject{Slicing.clip(a,0,120f)}}
    @Test fun savedKitSurvivesSourceDeletionAndNewProject(){val f=wav();val bytes=f.readBytes();val a=asset(f).copy(slices=listOf(0,24000,48000));val d=doc(Project(assets=listOf(a)).instrument(49){layered(a)});val library=InstrumentLibrary(temp.newFolder());val entry=library.save("Metal kit",d,49,{f},cancel);assertEquals(1,library.list().size);f.delete();val audio=temp.newFolder();val loaded=library.apply(entry.id,12,doc(),audio,cancel);assertTrue(loaded.valid());assertEquals(layered(a),loaded.project.instruments[12]);assertEquals(a.slices,loaded.project.assets.single().slices);assertArrayEquals(bytes,File(audio,loaded.project.assets.single().file).readBytes());library.delete(entry.id);assertTrue(File(audio,loaded.project.assets.single().file).isFile)}
    @Test fun presetLoadDeduplicatesCopiesInSameProject(){val f=wav();val a=asset(f);val lib=InstrumentLibrary(temp.newFolder());val entry=lib.save("Kit",doc(Project(assets=listOf(a)).instrument(0){layered(a)}),0,{f},cancel);val audio=temp.newFolder();val one=lib.apply(entry.id,0,doc(),audio,cancel);val two=lib.apply(entry.id,49,one,audio,cancel);assertEquals(1,two.project.assets.size);assertEquals(1,audio.listFiles()!!.size);assertEquals(two.project.instruments[0],two.project.instruments[49])}
    @Test fun damagedPresetRollsBackOnlyNewCopies(){val f=wav();val a=asset(f);val root=temp.newFolder();val lib=InstrumentLibrary(root);val entry=lib.save("Kit",doc(Project(assets=listOf(a)).instrument(0){layered(a)}),0,{f},cancel);File(root,"${entry.id}/source_0.wav").appendText("corrupt");val audio=temp.newFolder();val sentinel=File(audio,"existing.wav").also{it.writeText("keep")};reject{lib.apply(entry.id,0,doc(),audio,cancel)};assertEquals(listOf(sentinel.name),audio.listFiles()!!.map{it.name})}
    @Test fun failedAudioValidationCleansNewPresetCopies(){val f=wav();val a=asset(f);val lib=InstrumentLibrary(temp.newFolder());val entry=lib.save("Kit",doc(Project(assets=listOf(a)).instrument(0){layered(a)}),0,{f},cancel);val audio=temp.newFolder();reject{lib.apply(entry.id,0,doc(),audio,cancel){error("Playback budget")}};assertEquals(0,audio.listFiles()!!.size)}
    @Test fun cancelledPresetSavePublishesNothing(){val f=wav();val a=asset(f);val root=temp.newFolder();val lib=InstrumentLibrary(root);reject{lib.save("Kit",doc(Project(assets=listOf(a)).instrument(0){layered(a)}),0,{f},AtomicBoolean(true))};assertTrue(root.listFiles()!!.isEmpty());reject{lib.delete("../outside")}}
    @Test fun resampleSupports192kWithinExistingBudgets(){val d=doc(Project(clips=listOf(Clip(0).add(0.0,60,1.0))));Resampling.checkCapacity(d,Resampling.source(d,0,false),192000,1f);reject{Resampling.checkCapacity(d,d,384000,0f)}}
}
