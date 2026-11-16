package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.audio.AudioBridge
import com.weskestis.astrastudio.astra.*
import com.weskestis.astrastudio.fx.*
import com.weskestis.astrastudio.synth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapacityTests {
    private val presets=SynthSchema.presets.map{SynthPatch.initial().copy(name=it)}
    private fun doc(p: Project=Project())=ProjectDocument(p,145f,.7f,0f,80f,List(ProjectLimits.TRACKS){TrackControls()},List(ProjectLimits.TRACKS){presets[0]},List(ProjectLimits.TRACKS){FxConfig.initial()})
    private fun rejects(f: ()->Unit)=assertTrue(runCatching(f).isFailure)
    @Test fun trackFiftyHasEightIndependentClips(){
        var p=Project();repeat(8){scene->val slot=p.emptySlot(49)!!;assertEquals(scene*50+49,slot);p=p.put(Clip(slot).add(0.0,29+scene,1.0))}
        assertNull(p.emptySlot(49));assertEquals(50,AudioBridge.TRACK_COUNT)
        assertEquals(7,p.clip(399)!!.scene);assertEquals(49,p.clip(399)!!.track);assertTrue(p.valid())
        assertEquals(50,SequenceStats().active.size);assertEquals(50,FxStats().sidechain.size)
    }
    @Test fun everyLegacySlotAndRegionKeepsItsSceneAndTrack(){
        val slots=(0 until 128).map{it/16*50+it%16}
        val p=Project(clips=slots.map{Clip(it,length=1.0).add(0.0,40+it%12,.25)},regions=slots.mapIndexed{i,slot->Region(i+1,slot,(i/16).toDouble(),1.0)})
        val before=doc(p)
        for(version in 1..5){
            val json=legacyJson(before,version)
            assertEquals(127,json.getJSONArray("clips").getJSONObject(127).getInt("slot"))
            val migrated=ProjectCodec.decode(json.toString())
            assertEquals(before,migrated);assertEquals(365,migrated.project.clips.last().slot)
            assertEquals(migrated,ProjectCodec.decode(ProjectCodec.encode(migrated)))
        }
    }
    @Test fun legacyInstrumentsEffectsAndWarpStayOnTheirTrack(){
        val a=AudioAsset(0,"Tone","tone.wav",48000,96000,List(1024){0f})
        val p=Project(assets=listOf(a),clips=listOf(Clip(365,length=2.0,asset=0,last=96000,warp=true,sourceBpm=138.0,warpStart=100,pitch=-7.0)))
            .instrument(15){it.copy(mode=1,sample=it.sample.load(a))}.place(365,0.0)
        val before=doc(p).let{it.copy(effects=it.effects.toMutableList().also{fx->fx[15]=fx[15].parameter(FxSchema.ScSource,14f).parameter(FxSchema.ScOn,1f)})}
        assertEquals(before,ProjectCodec.decode(legacyJson(before,5).toString()))
    }
    @Test fun denseFullProjectFitsBothSerializationBudgets(){
        val notes=List(256){MidiNote(it+1,it*.25,.125,29+it%36,.85f)}
        val lanes=List(4){lane->List(64){AutoPoint(it.toDouble(),if(lane==2)1200f else .5f)}}
        val clips=List(400){Clip(it,length=64.0,notes=notes,automation=lanes)}
        val regions=List(2048){Region(it+1,it%50,(it/50)*64.0,64.0)}
        val d=doc(Project(clips=clips,regions=regions));assertTrue(d.valid())
        val wire=d.project.nativeWire();assertTrue(wire.size>400000);assertTrue(wire.size<=ProjectLimits.NATIVE_VALUES)
        val encoded=ProjectCodec.encode(d);assertTrue(encoded.toByteArray().size<ProjectLimits.JSON_BYTES)
        assertEquals(d,ProjectCodec.decode(encoded))
    }
    @Test fun highTracksAreSeparateForArrangementOverlapAndUndo(){
        val d=doc(Project(clips=listOf(Clip(49,length=1.0),Clip(99,length=1.0),Clip(48,length=1.0))))
        val next=d.copy(project=d.project.place(49,0.0).place(48,0.0).place(99,1.0));assertTrue(next.valid())
        rejects{next.project.place(99,0.0)}
        val h=ProjectHistory();h.commit(d,next);assertEquals(d,h.undo(next));assertEquals(next,h.redo(d))
    }
    @Test fun malformedOldAndNewDimensionsAreRejected(){
        for(slot in listOf(-1,400))assertFalse(Project(clips=listOf(Clip(slot))).valid())
        val old=legacyJson(doc(Project(clips=listOf(Clip(0)))),5);old.getJSONArray("clips").getJSONObject(0).put("slot",128);rejects{ProjectCodec.decode(old.toString())}
        val fresh=JSONObject(ProjectCodec.encode(doc()));fresh.put("trackCount",16);rejects{ProjectCodec.decode(fresh.toString())}
        assertFalse(doc().copy(tracks=List(49){TrackControls()}).valid())
        val p=Project(clips=listOf(Clip(49,length=1.0)),regions=List(2048){Region(it+1,49,it.toDouble(),1.0)})
        assertTrue(p.valid());rejects{p.place(49,2048.0)}
    }
    @Test fun astraCreatesAndRoutesTrackFifty(){
        val commands=listOf(AstraCommands.command("pattern","track" to 50,"scene" to 8,"style" to "riddim","root" to "F","scale" to "minor","bars" to 1),
            AstraCommands.command("sidechain","sourceTrack" to 50,"targetTrack" to 49),
            AstraCommands.command("place","track" to 50,"scene" to 8,"beat" to 0))
        val result=AstraCommands.compile(AstraCommands.envelope("Track 50",commands),doc(),presets).document
        assertNotNull(result.project.clip(399));assertEquals(49f,result.effects[48][FxSchema.ScSource],0f)
        assertEquals(399,result.project.regions.single().slot);assertEquals(result,ProjectCodec.decode(ProjectCodec.encode(result)))
        val text=OfflineCommands.compile("create a riddim bassline on track 50",399,doc())
        assertEquals(49,AstraCommands.compile(text,doc(),presets).document.project.clips.single().track)
        rejects{OfflineCommands.compile("create a riddim bassline on track 51",0,doc())}
    }
}
