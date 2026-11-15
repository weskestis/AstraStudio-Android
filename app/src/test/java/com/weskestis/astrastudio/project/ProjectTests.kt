package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.fx.FxConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ProjectTests {
    private fun document(p: Project=Project())=ProjectDocument(p,145f,.7f,0f,80f,List(16){TrackControls()},List(16){SynthPatch.initial()},List(16){FxConfig.initial()})
    private fun asset(id: Int=0)=AudioAsset(id,"Audio","sample-$id.wav",48000,48000,List(1024){0f})
    private fun rejects(block: ()->Unit){assertTrue(runCatching(block).isFailure)}
    @Test fun midiEditsPreserveBounds(){var c=Clip(0,length=4.0).add(3.9,200,3.0,2f);assertEquals(127,c.notes[0].pitch);assertEquals(.1,c.notes[0].length,1e-8);c=c.editNote(1,-1.0,-1,20.0,-1f);assertEquals(0.0,c.notes[0].beat,0.0);assertEquals(0,c.notes[0].pitch);assertEquals(4.0,c.notes[0].length,0.0);assertEquals(.001f,c.notes[0].velocity,0f);assertTrue(Project(clips=listOf(c)).valid())}
    @Test fun quantizeAndLegato(){val c=Clip(0,length=4.0).add(.13,48,.2).add(1.4,55,.1);val q=c.quantize(.25);assertEquals(.25,q.notes[0].beat,0.0);assertEquals(1.5,q.notes[1].beat,0.0);val l=q.legato();assertEquals(1.25,l.notes[0].length,0.0);assertEquals(2.5,l.notes[1].length,0.0)}
    @Test fun resizeDropsOutsideContent(){val c=Clip(0,length=8.0).add(1.0,60,5.0).add(6.0,61,1.0).point(0,7.0,.5f,.25).resize(4.0);assertEquals(1,c.notes.size);assertEquals(3.0,c.notes[0].length,0.0);assertTrue(c.automation[0].isEmpty())}
    @Test fun automationReplacesSnappedPoint(){val c=Clip(0).point(2,.12,500f,.25).point(2,.10,900f,.25).point(2,4.0,99999f,.25);assertEquals(2,c.automation[2].size);assertEquals(900f,c.automation[2][0].value,0f);assertEquals(18000f,c.automation[2][1].value,0f)}
    @Test fun capacityErrorsAreExplicit(){val c=Clip(0,notes=List(256){MidiNote(it+1,0.0,.1,60)});rejects{c.add(0.0,60,.1)};var points=Clip(0,length=128.0);repeat(64){points=points.point(0,it.toDouble(),1f,1.0)};rejects{points.point(0,65.0,1f,1.0)}}
    @Test fun duplicateCreatesIndependentSource(){val p=Project(clips=listOf(Clip(0).add(0.0,41,1.0))).duplicate(0,16);val edited=p.put(p.clip(16)!!.editNote(1,pitch=50));assertEquals(41,edited.clip(0)!!.notes[0].pitch);assertEquals(50,edited.clip(16)!!.notes[0].pitch);rejects{p.duplicate(0,16)}}
    @Test fun regionOverlapRejected(){val p=Project(clips=listOf(Clip(0,length=4.0))).place(0,0.0);rejects{p.place(0,3.0)};val next=p.duplicateRegion(1);assertEquals(4.0,next.regions[1].beat,0.0);rejects{next.editRegion(2,beat=2.0)}}
    @Test fun splitMidiKeepsLoopOffset(){val p=Project(clips=listOf(Clip(0,length=4.0)),regions=listOf(Region(1,0,0.0,8.0,1.0))).splitRegion(1,5.0,120f);assertEquals(2,p.regions.size);assertEquals(5.0,p.regions[1].beat,0.0);assertEquals(2.0,p.regions[1].offset,0.0);assertEquals(8.0,p.regions.sumOf{it.length},0.0)}
    @Test fun audioSplitUsesIntegerSamples(){val p=Project(assets=listOf(asset()),clips=listOf(Clip(0,length=2.0,asset=0,last=48000))).place(0,0.0).splitRegion(1,12345.4/24000,120f);assertEquals(12345L,p.regions[1].audioOffset);assertEquals(12345.0/24000,p.regions[1].beat,1e-12);assertEquals(2.0,p.regions.sumOf{it.length},0.0)}
    @Test fun missingAudioAndTraversalAreRejected(){assertFalse(Project(clips=listOf(Clip(0,asset=0,last=1))).valid());assertFalse(Project(assets=listOf(asset().copy(file="../secret.wav"))).valid());assertFalse(Project(assets=listOf(asset().copy(frames=Long.MAX_VALUE))).valid())}
    @Test fun nonfiniteOrOutOfRangeValuesRejected(){assertFalse(Project(loopEnd=Double.NaN).valid());assertFalse(Project(denominator=3).valid());assertFalse(Project(clips=listOf(Clip(0,notes=listOf(MidiNote(1,0.0,.5,60,Float.NaN))))).valid());assertFalse(document().copy(bpm=Float.NaN).valid())}
    @Test fun metadataAndMixRoundTrip(){val a=asset();val p=Project(name="MIDI + audio",assets=listOf(a),clips=listOf(Clip(0).add(0.0,41,.5).point(1,0.0,-.5f,.25),Clip(1,length=2.0,asset=0,last=a.frames))).place(0,0.0).place(1,0.0)
        val d=document(p).copy(tracks=List(16){TrackControls(gain=.7f,pan=.1f,armed=true,sendA=.3f,sendB=.2f,name="T$it")});assertEquals(d,ProjectCodec.decode(ProjectCodec.encode(d)));assertEquals(100,d.mixWire().size);assertEquals(.3f,d.mixWire()[8],0f)}
    @Test fun codecRejectsBadVersionsAndFractionalIds(){val d=document(Project(clips=listOf(Clip(0))));val json=JSONObject(ProjectCodec.encode(d));json.put("version",2);rejects{ProjectCodec.decode(json.toString())};json.put("version",1);json.getJSONArray("clips").getJSONObject(0).put("slot",.5);rejects{ProjectCodec.decode(json.toString())}}
    @Test fun codecRejectsBrokenPatchAndWrongArraySizes(){val json=JSONObject(ProjectCodec.encode(document()));json.getJSONArray("patches").put(0,"nonsense");rejects{ProjectCodec.decode(json.toString())};json.put("patches",org.json.JSONArray());rejects{ProjectCodec.decode(json.toString())};rejects{ProjectCodec.decode("{")}}
    @Test fun wireIncludesAllNativeState(){val c=Clip(17,length=4.0).add(1.0,41,.5).point(0,0.0,.4f,.25);val p=Project(mode=1,clips=listOf(c)).place(17,8.0);val w=p.nativeWire();assertEquals(1.0,w[1],0.0);assertEquals(1.0,w[7],0.0);assertEquals(1.0,w[8],0.0);assertEquals(17.0,w[9],0.0);assertEquals(29,w.size);assertEquals(8.0,w[w.size-4],0.0)}
    @Test fun signatureQuantization(){assertEquals(3.5,Project(numerator=7,denominator=8).launchBeats,0.0);assertEquals(.5,Project(quantization=2).launchBeats,0.0)}
    @Test fun removeClipRemovesReferences(){val p=Project(clips=listOf(Clip(0))).place(0,0.0).remove(0);assertTrue(p.clips.isEmpty());assertTrue(p.regions.isEmpty())}
    @Test fun historyCoalescesOnlyContinuousGesture(){val h=ProjectHistory();val a=document();val b=a.copy(bpm=138f);val c=b.copy(bpm=128f);h.commit(a,b,"tempo",100);h.commit(b,c,"tempo",200);assertEquals(a,h.undo(c));assertEquals(c,h.redo(a));h.commit(c,b,"tempo",1000);assertEquals(c,h.undo(b))}
    @Test fun recordingGroupIsOneUndo(){val h=ProjectHistory();val a=document();val b=a.copy(project=a.project.put(Clip(0)));val c=b.copy(project=b.project.put(Clip(0).add(0.0,41,1.0)));h.begin(a);h.commit(a,b);h.commit(b,c);h.end();assertEquals(a,h.undo(c));assertFalse(h.canUndo);assertEquals(c,h.redo(a))}
    @Test fun newEditDropsRedo(){val h=ProjectHistory();val a=document();val b=a.copy(bpm=138f);h.commit(a,b);h.undo(b);assertTrue(h.canRedo);h.commit(a,a.copy(bpm=128f));assertFalse(h.canRedo)}
    @Test fun starterIsEditableAndValid(){val d=StarterProject.create(List(8){SynthPatch.initial()});assertTrue(d.valid());assertEquals(10,d.project.clips.size);assertEquals(20,d.project.regions.size);assertEquals(64.0,d.project.endBeat,0.0);assertTrue(d.project.clips.all{it.notes.isNotEmpty()});assertEquals(d,ProjectCodec.decode(ProjectCodec.encode(d)))}
    @Test fun boundedCopyAndCancellation(){val bytes=ByteArray(1000){it.toByte()};val out=ByteArrayOutputStream();assertEquals(1000L,ProjectFiles.copy(ByteArrayInputStream(bytes),out,1000,AtomicBoolean(false)));assertArrayEquals(bytes,out.toByteArray());rejects{ProjectFiles.copy(ByteArrayInputStream(bytes),ByteArrayOutputStream(),999,AtomicBoolean(false))};rejects{ProjectFiles.copy(ByteArrayInputStream(bytes),ByteArrayOutputStream(),1000,AtomicBoolean(true))}}
}
