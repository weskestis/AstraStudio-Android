package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.synth.SynthSchema as S
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.fx.FxSchema as F

/** Original editable MIDI demonstration; every sound is synthesized by Astra. */
object StarterProject {
    fun create(presets: List<SynthPatch>): ProjectDocument {
        require(presets.size>=4)
        fun drum(name: String,osc: Float,noise: Float,decay: Float)=SynthPatch.initial()
            .parameter(S.OscALevel,osc).parameter(S.NoiseLevel,noise)
            .parameter(S.Filter1Cutoff,18000f).parameter(S.Filter2Cutoff,18000f)
            .parameter(S.Env1Attack,.001f).parameter(S.Env1Decay,decay).parameter(S.Env1Sustain,0f).parameter(S.Env1Release,.025f).copy(name=name)
        val kick=drum("Pulse kick",1f,0f,.20f).parameter(S.Env2Attack,.001f).parameter(S.Env2Decay,.045f).parameter(S.Env2Sustain,0f)
            .route(0,0,6f).route(0,1,3f).route(0,2,1f).copy(name="Pulse kick")
        val snare=drum("Steel snare",.52f,.86f,.16f).parameter(S.Filter1Type,1f).parameter(S.Filter1Cutoff,100f).copy(name="Steel snare")
        val hat=drum("Wire hat",0f,1f,.035f).parameter(S.Filter1Type,1f).parameter(S.Filter1Cutoff,7000f).copy(name="Wire hat")
        val patches=List(16){when(it){0->kick;1->snare;2->hat;3->presets[1];4->presets[3];else->SynthPatch.initial()}}
        val tracks=List(16){when(it){0->TrackControls(gain=.95f,name="Kick");1->TrackControls(gain=.85f,sendA=.08f,name="Snare");2->TrackControls(gain=.32f,pan=.1f,name="Hats");3->TrackControls(gain=.64f,name="Liquid bass");4->TrackControls(gain=.30f,sendA=.16f,sendB=.12f,name="Prism lead");else->TrackControls()}}
        var p=Project(name="Green circuit",loop=true,loopEnd=64.0)
        repeat(2){scene->
            var kickClip=Clip(scene*16,"Kick ${scene+1}")
            listOf(0.0,3.5,4.0,8.0,11.5,12.0,if(scene==0)14.75 else 15.0).forEach{kickClip=kickClip.add(it,36,.4,1f)}
            var snareClip=Clip(scene*16+1,"Backbeat ${scene+1}")
            listOf(2.0,6.0,10.0,14.0).forEach{snareClip=snareClip.add(it,50,.5,.95f)}
            var hatClip=Clip(scene*16+2,"Hats ${scene+1}")
            repeat(32){i->hatClip=hatClip.add(i*.5,66,.12,if(i%2==0).55f else .8f)}
            if(scene==1)listOf(7.25,7.75,15.25,15.75).forEach{hatClip=hatClip.add(it,66,.1,.65f)}
            var bass=Clip(scene*16+3,"F minor ${if(scene==0)"stomp" else "answer"}")
            val keys=if(scene==0)listOf(29,29,36,29,32,29,39,36)else listOf(29,41,36,32,29,36,39,32)
            repeat(16){i->val at=i.toDouble()+if(scene==1&&i%4==3).25 else 0.0;bass=bass.add(at,keys[i%8],if(i%4==0).72 else .42,.86f)}
            bass=bass.copy(automation=listOf(emptyList(),emptyList(),listOf(AutoPoint(0.0,5000f),AutoPoint(8.0,12000f),AutoPoint(15.9,5000f)),emptyList()))
            var lead=Clip(scene*16+4,"Prism ${scene+1}")
            val melody=if(scene==0)listOf(65,68,72,75,72,68,63,68)else listOf(65,72,77,75,72,68,70,63)
            repeat(32){i->lead=lead.add(i*.5,melody[i%8],.3,if(i%4==0).82f else .62f)}
            listOf(kickClip,snareClip,hatClip,bass,lead).forEach{p=p.put(it)}
        }
        repeat(4){section->repeat(5){track->p=p.place((section%2)*16+track,section*16.0)}}
        val fx=List(16){if(it==3||it==4)FxConfig.initial().parameter(F.ScOn,1f).parameter(F.ScSource,0f).parameter(F.ScThreshold,-28f).parameter(F.ScRatio,6f)else FxConfig.initial()}
        return ProjectDocument(p,145f,.8f,2f,80f,tracks,patches,fx).also{require(it.valid())}
    }
}
