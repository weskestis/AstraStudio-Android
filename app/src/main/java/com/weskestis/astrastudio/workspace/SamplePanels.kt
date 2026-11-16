package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.fx.ValueSlider
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.project.*
import kotlin.math.roundToInt

@Composable internal fun SampleInstrumentPanel(s: StudioState,t: Int,slot: Int,service: AudioService,start: ()->Unit,browse: (Int)->Unit) {
    val instrument=s.project.instruments[t]
    var selected by rememberSaveable(t){mutableIntStateOf(0)}
    var editing by rememberSaveable(t){mutableStateOf(false)}
    PanelBackHandler(editing){editing=false;service.releaseTouchNotes()}
    Tools{listOf("Synth","Sampler","Drum rack").forEachIndexed{i,label->FilterChip(instrument.mode==i,{service.instrument(t,i);editing=false},label={Text(label)},modifier=Modifier.testTag("Instrument $i"))}}
    Tools{Action(if(s.stats.running)"Audio on" else "Start audio",tag="Sample audio"){if(!s.stats.running)start()};Action("Stop notes"){service.panic()};Action(if(s.recording>=0)"Finish recording" else "Record MIDI",s.stats.running&&!s.busy,"Sample record"){service.record(slot)}}
    Text(s.message,color=Muted,fontSize=12.sp)
    if(instrument.mode==0){Text("Choose Sampler or Drum rack to play samples. Your synth patch is retained.");return}
    if(instrument.mode==2&&!editing){
        Text("DRUM RACK · MIDI 36–51",color=Green,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=8.dp))
        Text("Tap to play and select. Edit pad opens its sound settings. Record MIDI captures your performance.",fontSize=12.sp,color=Muted)
        for(row in 0..3)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            for(col in 0..3){val pad=row*4+col;val zone=instrument.pads[pad];val asset=s.project.asset(zone.asset)
                val label="Pad ${pad+1}: ${asset?.name?:"Empty"}"
                Box(Modifier.weight(1f).heightIn(min=76.dp).padding(vertical=3.dp).background(if(selected==pad)Color(0xFF174D26)else Color(0xFF102016)).border(1.dp,if(selected==pad)Green else Color(0xFF32633E))
                    .testTag("Pad $pad").semantics{contentDescription=label;role=Role.Button;onClick{selected=pad;if(s.stats.running&&asset!=null)service.hitPad(t,pad);true}}
                    .pointerInput(t,pad,s.stats.running,asset?.id){detectTapGestures(onPress={selected=pad;if(s.stats.running&&asset!=null){val id=service.nextTouchId();service.noteOn(t,36+pad,id);try{tryAwaitRelease()}finally{service.noteOff(t,id)}}},onLongPress={selected=pad;editing=true})},contentAlignment=Alignment.Center){
                    Column(Modifier.padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("${pad+1} · ${36+pad}",fontSize=11.sp,color=Green);Text(asset?.name?:"Empty",fontSize=11.sp,maxLines=2)}
                }
            }
        }
        Tools{Action("Edit pad ${selected+1}",tag="Edit pad"){editing=true};Action("Load sample",!s.busy){browse(selected)};Action("Load starter kit",!s.busy,"Starter kit"){service.factoryKit(t)}}
        Text("The starter kit contains 16 original drums and an 808. Loading it replaces this track's pad assignments; Undo restores them.",fontSize=12.sp,color=Muted)
    } else {
        val pad=if(instrument.mode==2)selected else -1
        val zone=instrument.zone(pad)
        if(pad>=0)Text("PAD ${pad+1} · MIDI ${36+pad}",color=Green,modifier=Modifier.testTag("Pad editor"))
        ZoneEditor(zone,s.project.asset(zone.asset),pad,change={edit->service.sampleEdit(t,pad,edit)},browse={browse(pad)})
        if(pad<0){Text("PLAY THE SAMPLER",color=Green,modifier=Modifier.padding(top=12.dp));Keyboard(t,s.stats.running,service)}
    }
}

@Composable private fun ZoneEditor(z: SampleZone,a: AudioAsset?,pad: Int,change: ((SampleZone)->SampleZone)->Unit,browse: ()->Unit) {
    Text(a?.name?:"No sample assigned",color=Green,fontWeight=FontWeight.Bold)
    Action(if(a==null)"Choose sample" else "Replace sample",tag="Choose sample",click=browse)
    if(a==null)return
    SampleWaveform(a,z.first,z.last,z.loopFirst,z.loopLast,z.loop)
    var first by remember(a.id,z.first){mutableStateOf(z.first.toString())};var last by remember(a.id,z.last){mutableStateOf(z.last.toString())}
    var loopFirst by remember(a.id,z.loopFirst){mutableStateOf(z.loopFirst.toString())};var loopLast by remember(a.id,z.loopLast){mutableStateOf(z.loopLast.toString())}
    var ranges by rememberSaveable{mutableStateOf(false)}
    PanelBackHandler(ranges,depth=1){ranges=false}
    Tools{
        FilterChip(z.reverse,{change{it.copy(reverse=!it.reverse)}},label={Text("Reverse")},modifier=Modifier.testTag("Sample reverse"))
        FilterChip(z.loop,{change{it.copy(loop=!it.loop,oneShot=false)}},label={Text("Loop")},modifier=Modifier.testTag("Sample loop"))
        FilterChip(z.oneShot,{change{it.copy(oneShot=!it.oneShot,loop=false)}},label={Text("One shot")})
        Action("Trim / loop frames"){ranges=!ranges}
    }
    Text("${number(z.first.toDouble()/a.rate)}–${number(z.last.toDouble()/a.rate)} s · ${a.rate} Hz. One shot plays through note-off; loop repeats while held.",fontSize=12.sp,color=Muted)
    if(ranges){
        NumberField("Start frame",first,{first=it});NumberField("End frame (exclusive)",last,{last=it});NumberField("Loop start frame",loopFirst,{loopFirst=it});NumberField("Loop end frame",loopLast,{loopLast=it})
        val f=first.toLongOrNull();val l=last.toLongOrNull();val lf=loopFirst.toLongOrNull();val ll=loopLast.toLongOrNull()
        Action("Apply sample range",f!=null&&l!=null&&lf!=null&&ll!=null&&f>=0&&l<=a.frames&&f<l&&lf>=f&&ll<=l&&lf<ll,"Apply sample range"){change{it.copy(first=f!!,last=l!!,loopFirst=lf!!,loopLast=ll!!)}}
        Action("Reset full range"){change{it.copy(first=0,last=a.frames,loopFirst=0,loopLast=a.frames)}}
    }
    if(pad<0)ValueSlider("Root MIDI note",z.root.toFloat(),0f..127f,change={v->change{it.copy(root=v.roundToInt())}})
    ValueSlider("Pitch · semitones",z.pitch,-48f..48f,change={v->change{it.copy(pitch=v)}})
    ValueSlider("Volume",z.gain,0f..2f,change={v->change{it.copy(gain=v)}})
    ValueSlider("Pan",z.pan,-1f..1f,change={v->change{it.copy(pan=v)}})
    ValueSlider("Attack · seconds",z.attack,0f..5f,change={v->change{it.copy(attack=v)}})
    ValueSlider("Decay · seconds",z.decay,.001f..30f,true,change={v->change{it.copy(decay=v)}})
    ValueSlider("Sustain",z.sustain,0f..1f,change={v->change{it.copy(sustain=v)}})
    ValueSlider("Release · seconds",z.release,.005f..10f,true,change={v->change{it.copy(release=v)}})
    if(pad>=0)Tools{Text("Choke",color=Muted);(0..8).forEach{group->FilterChip(z.choke==group,{change{it.copy(choke=group)}},label={Text(if(group==0)"Off" else "$group")})}}
    Text("Velocity controls volume. Settings apply to new notes; active notes finish with their original settings. Transposing changes pitch and duration together.",color=Muted,fontSize=12.sp)
}

@Composable internal fun SampleWaveform(a: AudioAsset,first: Long=0,last: Long=a.frames,loopFirst: Long=0,loopLast: Long=a.frames,loop: Boolean=false){
    Canvas(Modifier.fillMaxWidth().height(110.dp).background(Color.Black).testTag("Sample waveform").semantics{contentDescription="Waveform for ${a.name}"}){
        for(i in 0 until 512){val x=size.width*i/512;drawLine(Green,Offset(x,size.height*(.5f-a.waveform[i*2].coerceIn(-1f,1f)*.45f)),Offset(x,size.height*(.5f-a.waveform[i*2+1].coerceIn(-1f,1f)*.45f)),1f)}
        fun line(frame: Long,color: Color){val x=(frame.toDouble()/a.frames*size.width).toFloat();drawLine(color,Offset(x,0f),Offset(x,size.height),3f)}
        line(first,Color.White);line(last,Color.White);if(loop){line(loopFirst,Color.Yellow);line(loopLast,Color.Yellow)}
    }
}

@Composable internal fun SampleBrowser(s: StudioState,t: Int,slot: Int,targetPad: Int?,service: AudioService,start: ()->Unit,importFiles: ()->Unit,importPack: ()->Unit,assigned: ()->Unit){
    var query by rememberSaveable{mutableStateOf("")};var favorites by rememberSaveable{mutableStateOf(false)};var group by rememberSaveable{mutableStateOf("")};var selected by rememberSaveable{mutableIntStateOf(-1)}
    PanelBackHandler(selected>=0){selected=-1;service.stopPreview()}
    val asset=s.project.asset(selected)
    if(asset!=null){
        Text(asset.name,fontWeight=FontWeight.Bold,color=Green,modifier=Modifier.testTag("Sample details"));SampleWaveform(asset)
        Text("${number(asset.frames.toDouble()/asset.rate)} seconds · ${asset.rate} Hz · ${asset.frames} frames",fontSize=12.sp,color=Muted)
        Tools{Action("▶ Preview",s.stats.running&&!s.busy,"Preview sample"){service.previewSample(asset.id)};Action("Stop preview"){service.stopPreview()};Action(if(asset.favorite)"★ Favorite" else "☆ Favorite",tag="Favorite sample"){service.sampleFavorite(asset.id)}}
        if(!s.stats.running)Action("Start audio to preview",click=start)
        if(targetPad!=null)Action(if(targetPad<0)"Use in sampler" else "Use on pad ${targetPad+1}",!s.busy,"Assign sample"){service.assignSample(t,targetPad,asset.id);assigned()}
        else Tools{Action("Use in sampler",!s.busy){service.assignSample(t,-1,asset.id);assigned()};Action("Add audio clip",!s.busy){service.sampleClip(asset.id,slot)}}
        var name by remember(asset.id,asset.group){mutableStateOf(asset.group)}
        OutlinedTextField(name,{name=it.take(64)},label={Text("Collection")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Action("Save collection"){service.sampleGroup(asset.id,name)}
        Text("Preview plays up to 10 seconds without track effects. Samples and collections travel with the saved project.",fontSize=12.sp,color=Muted)
        return
    }
    Text("SAMPLES · ${s.project.assets.size}/64",color=Green,fontWeight=FontWeight.Bold)
    Tools{Action("Import samples",!s.busy,click=importFiles);Action("Import ZIP pack",!s.busy,click=importPack);FilterChip(favorites,{favorites=!favorites},label={Text("★ Favorites")})}
    Text("Select multiple audio files, or a ZIP pack. Mono/stereo WAV and formats supported by Android. 2 GiB per file; 8 GiB stereo-float-equivalent sources per project. Large samples stream through fixed audio pages.",fontSize=12.sp,color=Muted)
    OutlinedTextField(query,{query=it},label={Text("Search samples")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Sample search"))
    Tools{FilterChip(group=="",{group=""},label={Text("All")});s.project.assets.map{it.group}.distinct().sorted().forEach{label->FilterChip(group==label,{group=label},label={Text(label.ifBlank{"Unsorted"})})}}
    val matches=s.project.assets.filter{(!favorites||it.favorite)&&(group.isEmpty()||it.group==group)&&it.name.contains(query,true)}
    if(matches.isEmpty())Text("No samples match. Import sounds or load the starter kit from Drum rack.",modifier=Modifier.padding(vertical=16.dp),color=Muted)
    matches.forEach{a->OutlinedButton({selected=a.id},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("Sample ${a.id}")){Column(Modifier.fillMaxWidth()){Text((if(a.favorite)"★ " else "")+a.name,maxLines=2);Text("${a.group} · ${number(a.frames.toDouble()/a.rate)} s",fontSize=11.sp,color=Muted)}}}
}

@Composable internal fun ResamplePanel(s: StudioState,slot: Int,service: AudioService,browse: ()->Unit){
    var whole by rememberSaveable{mutableStateOf(false)};var tail by rememberSaveable{mutableStateOf("1")}
    Text("TURN A PHRASE INTO A SAMPLE",color=Green,fontWeight=FontWeight.Bold)
    Tools{FilterChip(!whole,{whole=false},label={Text("Selected clip")});FilterChip(whole,{whole=true},label={Text("Arrangement")})}
    Text(if(whole)"Render the full arrangement once, including effects and sends." else "Render ${s.project.clip(slot)?.name?:"the selected clip"} once in isolation, including its effects, sends and master processing. Other tracks do not drive sidechains in an isolated bounce.",fontSize=13.sp)
    NumberField("Effect tail · seconds (0–30)",tail,{tail=it})
    Text("Bounce instrument creates one clip cycle before insert effects, mixer, sends and master. The new clip stays on this track, retains automation and can follow tempo with Warp. Original notes/audio stay available. Instrument release across repeated cycles may differ; this is a phrase bounce, not full-track freeze.",fontSize=12.sp,color=Muted)
    Action("Bounce instrument to clip",!s.busy&&s.project.clip(slot)!=null,"Bounce instrument"){service.bounceInstrument(slot)}
    Action("Resample to browser",!s.busy&&tail.toFloatOrNull()?.let{it.isFinite()&&it in 0f..30f}==true&&(if(whole)s.project.regions.isNotEmpty()else s.project.clip(slot)!=null),"Render resample"){service.resample(slot,whole,tail.toFloat())}
    Text(s.message,color=Green,modifier=Modifier.padding(vertical=12.dp))
    Action("Open samples",click=browse)
    Text("Creates a 48 kHz, 32-bit float WAV in the Resampled collection. The source remains editable. Use the result on a fresh track to avoid processing its effects twice.",fontSize=12.sp,color=Muted)
}
