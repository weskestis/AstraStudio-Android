package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.project.*
import kotlin.math.*

@Composable internal fun TempoPanel(s: StudioState,service: AudioService){
    val p=s.project;val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var beat by rememberSaveable{mutableStateOf("16")};var bpm by rememberSaveable{mutableStateOf(s.bpm.toString())};var ramp by rememberSaveable{mutableStateOf(false)}
    Text("TEMPO / SHAPE THE ARRANGEMENT",color=Green,fontWeight=FontWeight.Bold)
    Text("Add a point for a sudden tempo change. Ramp to next gradually reaches the following point's BPM. Positions use quarter-note beats starting at 0.",fontSize=12.sp,color=Muted)
    FilterChip(p.tempoEnabled,{service.projectEdit{it.copy(tempoEnabled=!it.tempoEnabled,tempoPoints=it.tempoPoints.ifEmpty{listOf(TempoPoint(0.0,s.bpm))})}},label={Text("Arrangement tempo map")},modifier=Modifier.testTag("Tempo map enabled"))
    Text("Session uses ${number(s.bpm.toDouble())} BPM. The enabled map controls Arrangement playback and export. Enable Warp on an audio clip to follow tempo changes while preserving pitch. Unwarped clips retain their original speed.",fontSize=12.sp,color=Muted)
    if(p.tempoPoints.isNotEmpty())Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical=12.dp).background(Color.Black).testTag("Tempo curve")){
        val end=max(16.0,p.tempoPoints.last().beat);fun xy(x: Double,y: Double)=Offset((x/end*size.width).toFloat(),(size.height*(1-(y-30)/270)).toFloat())
        p.tempoPoints.forEachIndexed{i,a->val b=p.tempoPoints.getOrNull(i+1)
            val last=b?.beat?:end;drawLine(Green,xy(a.beat,a.bpm.toDouble()),xy(last,if(a.ramp&&b!=null)b.bpm.toDouble()else a.bpm.toDouble()),3f)
            if(b!=null&&!a.ramp)drawLine(Green,xy(b.beat,a.bpm.toDouble()),xy(b.beat,b.bpm.toDouble()),2f)
            drawCircle(Color.White,4f,xy(a.beat,a.bpm.toDouble()))
        }
    }
    OutlinedTextField(beat,{beat=it},label={Text("Beat position")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Tempo beat"))
    OutlinedTextField(bpm,{bpm=it},label={Text("BPM · 30–300")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Tempo bpm"))
    FilterChip(ramp,{ramp=!ramp},label={Text("Ramp to next point")},modifier=Modifier.testTag("Tempo ramp"))
    val b=beat.toDoubleOrNull();val v=bpm.toFloatOrNull();val valid=b?.isFinite()==true&&b in 0.0..65536.0&&v?.isFinite()==true&&v in 30f..300f
    Action("Save tempo point",valid&&!s.busy,"Save tempo point"){focus.clearFocus();keyboard?.hide();service.projectEdit{old->
        val nodes=old.tempoPoints.ifEmpty{listOf(TempoPoint(0.0,s.bpm))}.filter{it.beat!=b}+TempoPoint(b!!,v!!,ramp)
        old.copy(tempoPoints=nodes.sortedBy{it.beat},tempoEnabled=true)
    }}
    Text("${p.tempoPoints.size}/128 points · tap a point to edit",color=Muted,fontSize=12.sp)
    p.tempoPoints.forEachIndexed{i,point->Tools{
        Action("${number(point.beat)} beats · ${number(point.bpm.toDouble())} BPM${if(point.ramp)" ↗" else ""}",tag="Tempo point $i"){beat=point.beat.toString();bpm=point.bpm.toString();ramp=point.ramp}
        if(i>0)Action("Remove",tag="Remove tempo $i"){service.projectEdit{it.copy(tempoPoints=it.tempoPoints.filter{n->n.beat!=point.beat})}}
    }}
    if(p.tempoPoints.isNotEmpty())Action("Clear tempo map"){service.projectEdit{it.copy(tempoPoints=emptyList(),tempoEnabled=false)}}
    Text("The first point stays at beat 0. Undo restores every edit. Disabling the map keeps its points.",fontSize=12.sp,color=Muted)
}

@Composable internal fun StretchPanel(s: StudioState,slot: Int,service: AudioService,openSamples: ()->Unit,resample: ()->Unit){
    Text("STRETCH / KEEP THE PITCH",color=Green,fontWeight=FontWeight.Bold)
    val c=s.project.clip(slot);val a=c?.let{s.project.asset(it.asset)}
    if(c==null||a==null){Text("Select an audio clip. Resample a MIDI clip first to stretch its sound.");Action("Resample selected clip",c!=null,click=resample);return}
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val original=(c.last-c.first).toDouble()/a.rate
    var beats by rememberSaveable(slot,c.first,c.last){mutableStateOf(String.format(java.util.Locale.US,"%.6f",original*s.bpm/60).trimEnd('0').trimEnd('.'))}
    var pitch by rememberSaveable(slot){mutableStateOf("0")}
    Text(c.name,color=Green);SampleWaveform(a,c.first,c.last)
    Text("Source: ${number(original)} seconds · ${a.rate} Hz. Rendered at the source rate as 32-bit float stereo.",fontSize=12.sp,color=Muted)
    OutlinedTextField(beats,{beats=it},label={Text("New length · quarter-note beats")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Stretch beats"))
    OutlinedTextField(pitch,{pitch=it},label={Text("Pitch change · semitones")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Stretch pitch"))
    val target=beats.toDoubleOrNull();val shift=pitch.toDoubleOrNull()
    val result=remember(s.project,s.bpm,slot,beats,pitch){runCatching{StretchSettings.outputFrames(s.project,c,s.bpm,target?:Double.NaN,shift?:Double.NaN)}}
    val frames=result.getOrNull();val ratio=frames?.toDouble()?.div(c.last-c.first)
    if(frames!=null)Text("${number(frames.toDouble()/a.rate)} seconds · ${number(ratio!!*100)}% length at ${number(s.bpm.toDouble())} BPM",color=Green)
    else Text(result.exceptionOrNull()?.message?:"Enter a valid length and pitch.",fontSize=12.sp,color=Muted)
    Text("Pitch and duration are independent. Best transparency is usually near 75–150% length; large shifts can soften attacks or add texture. No automatic loudness boost or normalization is applied.",fontSize=12.sp,color=Muted)
    Text("This creates a new clip in the next free scene on this track and a sample in Stretched. Originals remain editable. The result has a fixed duration and does not automatically follow later BPM changes.",fontSize=12.sp,color=Muted)
    Action("Render stretch",frames!=null&&!s.busy&&s.project.emptySlot(slot%16)!=null,"Render stretch"){focus.clearFocus();keyboard?.hide();service.stretchClip(slot,target!!,shift!!)}
    if(s.project.emptySlot(slot%16)==null)Text("Free a clip slot on this track first.",color=Green)
    Text(s.message,color=Green,modifier=Modifier.padding(vertical=12.dp))
    Action("Open samples",click=openSamples)
}
