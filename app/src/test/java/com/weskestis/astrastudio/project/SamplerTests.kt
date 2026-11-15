package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.workspace.PanelNavigation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SamplerTests {
    private val a=AudioAsset(0,"Hat","hat.wav",48000,48000,List(1024){0f},true,"Drums")
    private fun d(p: Project=Project())=ProjectDocument(p,120f,.7f,0f,80f,List(16){TrackControls()},List(16){SynthPatch.initial()},List(16){FxConfig.initial()})
    private fun reject(block: ()->Unit)=assertTrue(runCatching(block).isFailure)
    @Test fun rangesAndMissingSamplesRejected(){val z=SampleZone().load(a);assertTrue(z.valid(listOf(a)));assertFalse(z.valid(emptyList()));assertFalse(z.copy(last=48001).valid(listOf(a)));assertFalse(z.copy(first=100,loopFirst=0).valid(listOf(a)));assertFalse(z.copy(loopFirst=20,loopLast=20).valid(listOf(a)));assertFalse(z.copy(loop=true,oneShot=true).valid(listOf(a)));assertFalse(z.copy(asset=-1).valid(listOf(a)))}
    @Test fun envelopeAndPitchRejectInvalidNumbers(){val z=SampleZone().load(a);for(bad in listOf(z.copy(pitch=Float.NaN),z.copy(gain=Float.POSITIVE_INFINITY),z.copy(pan=2f),z.copy(choke=9),z.copy(attack=-1f),z.copy(decay=0f),z.copy(release=0f),z.copy(sustain=2f),z.copy(root=128)))assertFalse(bad.valid(listOf(a)))}
    @Test fun sampleAssignmentsPersistWithFavorites(){val p=Project(assets=listOf(a)).instrument(4){it.copy(mode=2).edit(7){z->z.load(a).copy(reverse=true,pitch=-7f,choke=2)}};assertTrue(p.valid());assertEquals(d(p),ProjectCodec.decode(ProjectCodec.encode(d(p))));assertEquals(0,p.instruments[4].pads[7].asset);assertEquals(-1,p.instruments[4].pads[6].asset)}
    @Test fun phaseFourAndFiveProjectsMigrateToSynth(){val json=JSONObject(ProjectCodec.encode(d())).put("version",1);json.remove("instruments");val loaded=ProjectCodec.decode(json.toString());assertEquals(List(16){SampleInstrument()},loaded.project.instruments);assertTrue(loaded.valid())}
    @Test fun versionTwoRequiresCompleteInstrumentState(){val json=JSONObject(ProjectCodec.encode(d()));json.remove("instruments");reject{ProjectCodec.decode(json.toString())}}
    @Test fun sampleWireIsBoundedAndStable(){val p=Project(assets=listOf(a)).instrument(0){it.copy(mode=1,sample=SampleZone().load(a).copy(reverse=true))};val w=p.nativeWire();assertEquals(2.0,w[0],0.0);assertEquals(9+16*290,w.size);assertEquals(1.0,w[9],0.0);assertEquals(48000.0,w[12],0.0);assertEquals(1.0,w[23],0.0)}
    @Test fun codecRejectsFractionalFramesAndBadFlags(){val json=JSONObject(ProjectCodec.encode(d(Project(assets=listOf(a)).instrument(0){it.copy(mode=1,sample=SampleZone().load(a))})));
        val wire=json.getJSONArray("instruments").getJSONArray(0);wire.put(2,.5);reject{ProjectCodec.decode(json.toString())};wire.put(2,0);wire.put(14,2);reject{ProjectCodec.decode(json.toString())}}
    @Test fun resampleIsolatesClipAndRetainsItsProcessing(){val p=Project(clips=listOf(Clip(0,length=4.0).add(0.0,60,1.0),Clip(1,length=8.0))).place(0,12.0).place(1,0.0);val original=d(p);val bounce=Resampling.source(original,0,false);assertEquals(listOf(Region(1,0,0.0,4.0)),bounce.project.regions);assertEquals(listOf(0),bounce.tracks.indices.filter{bounce.tracks[it].solo});assertEquals(original.effects,bounce.effects);assertEquals(12.0,original.project.regions[0].beat,0.0);assertEquals(original,Resampling.source(original,0,true))}
    @Test fun resampleRejectsMissingSourceAndOversizedAudio(){reject{Resampling.source(d(),0,false)};reject{Resampling.source(d(),0,true)};val long=d(Project(clips=listOf(Clip(0,length=65536.0))).place(0,0.0));reject{Resampling.checkCapacity(d(),long,48000,1f)};val small=d(Project(clips=listOf(Clip(0,length=4.0))).place(0,0.0));Resampling.checkCapacity(d(),small,48000,1f);reject{Resampling.checkCapacity(d(),small,48000,Float.NaN)}}
    @Test fun assignmentUndoRestoresEntirePad(){val before=d(Project(assets=listOf(a)));val after=before.copy(project=before.project.instrument(0){it.copy(mode=2).edit(0){z->z.load(a)}});val h=ProjectHistory();h.commit(before,after);assertEquals(before,h.undo(after));assertEquals(after,h.redo(before))}
    @Test fun factoryKitIsDeterministicAndCancellable(){val one=File.createTempFile("astra-kit",".wav");val two=File.createTempFile("astra-kit",".wav");try{FactorySamples.write(6,one,AtomicBoolean(false));FactorySamples.write(6,two,AtomicBoolean(false));assertArrayEquals(one.readBytes(),two.readBytes());assertEquals("RIFF",String(one.readBytes(),0,4));assertTrue(one.length()>1000);reject{FactorySamples.write(0,two,AtomicBoolean(true))}}finally{one.delete();two.delete()}}
    @Test fun astraDrumsUseRackPadsAndPresetReturnsToSynth(){
        val original=d(Project(assets=listOf(a)).instrument(0){it.copy(mode=2).edit(2){z->z.load(a)}})
        val presets=List(8){SynthPatch.initial()}
        val commands=com.weskestis.astrastudio.astra.AstraCommands
        val pattern=commands.envelope("Snare",listOf(commands.command("pattern","track" to 1,"scene" to 1,"style" to "snare")))
        val after=commands.compile(pattern,original,presets).document
        assertTrue(after.project.clip(0)!!.notes.all{it.pitch==38})
        val load=commands.envelope("Synth",listOf(commands.command("preset","track" to 1,"preset" to "FM Alloy")))
        val synth=commands.compile(load,after,presets).document
        assertEquals(0,synth.project.instruments[0].mode);assertEquals(0,synth.project.instruments[0].pads[2].asset)
    }
    @Test fun nestedBackPopsMostRecentActiveChild(){val nav=PanelNavigation();val calls=mutableListOf<String>();val a=Any();val b=Any();var inner=true
        nav.add(a){calls+="panel";true};nav.add(b){if(inner){inner=false;calls+="detail";true}else false};assertTrue(nav.back());assertTrue(nav.back());assertEquals(listOf("detail","panel"),calls);nav.remove(b);nav.remove(a);assertFalse(nav.back())}
    @Test fun deeperPageWinsWhenParentRegistersAfterChild(){val nav=PanelNavigation();val calls=mutableListOf<String>();var inner=true
        nav.add(Any(),depth=1){if(inner){inner=false;calls+="trim";true}else false};nav.add(Any()){calls+="pad";true}
        assertTrue(nav.back());assertTrue(nav.back());assertEquals(listOf("trim","pad"),calls)}
}
