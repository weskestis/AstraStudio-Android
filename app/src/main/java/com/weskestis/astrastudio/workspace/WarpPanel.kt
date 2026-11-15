package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.project.*

@Composable internal fun WarpPanel(s: StudioState,slot: Int,service: AudioService,resample: ()->Unit){
    Text("WARP / FOLLOW THE SONG",color=Green,fontWeight=FontWeight.Bold)
    val c=s.project.clip(slot);val a=c?.let{s.project.asset(it.asset)}
    if(c==null||a==null){Text("Select an audio clip. Resample a MIDI clip first to warp its sound.");Action("Resample selected clip",c!=null,click=resample);return}
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var enabled by rememberSaveable(slot,c.warp){mutableStateOf(c.warp)}
    var bpm by rememberSaveable(slot,c.sourceBpm){mutableStateOf(c.sourceBpm.toString())}
    var downbeat by rememberSaveable(slot,c.first,c.warpStart){mutableStateOf((c.first+c.warpStart).toString())}
    var pitch by rememberSaveable(slot,c.pitch){mutableStateOf(c.pitch.toString())}
    Text(c.name,color=Green);SampleWaveform(a,c.first,c.last)
    Text("Set the recording's original tempo, then enable Warp. Its beats follow Session BPM or the Arrangement tempo map without changing pitch. Pitch shift is independent.",fontSize=12.sp,color=Muted)
    FilterChip(enabled,{enabled=!enabled},label={Text("Warp enabled")},modifier=Modifier.testTag("Warp enabled"))
    OutlinedTextField(bpm,{bpm=it},label={Text("Source BPM · 30–300")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("Warp source BPM"))
    OutlinedTextField(downbeat,{downbeat=it},label={Text("Downbeat · source frame")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("Warp downbeat"))
    Text("Beat 0 begins at this frame. Range ${c.first}–${c.last-1} · ${a.rate} Hz. Audio before the downbeat is retained in the original.",fontSize=12.sp,color=Muted)
    OutlinedTextField(pitch,{pitch=it},label={Text("Pitch · −24 to +24 semitones")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth().testTag("Warp pitch"))
    val frame=downbeat.toLongOrNull();val tempo=bpm.toDoubleOrNull();val shift=pitch.toDoubleOrNull()
    val result=runCatching{WarpSettings.apply(s.project,slot,enabled,tempo?:Double.NaN,frame?:-1,shift?:Double.NaN)}
    Action("Fit source to ${number(c.length)} beats",frame!=null&&frame in c.first until c.last,"Warp fit"){bpm=String.format(java.util.Locale.US,"%.6f",WarpSettings.fittedTempo(c,a,frame!!))}
    Text("Fit calculates source BPM from this clip's beat length and the audio after the downbeat. It does not detect tempo. For uneven recordings, detailed warp-marker editing is still planned.",fontSize=12.sp,color=Muted)
    if(result.isSuccess&&tempo!=null&&frame!=null)Text("Source covers ${number((c.last-frame).toDouble()/a.rate*tempo/60)} beats · clip loops every ${number(c.length)} beats",color=Green,modifier=Modifier.testTag("Warp summary"))
    else Text(result.exceptionOrNull()?.message?:"Enter valid values.",color=Green)
    Action("Apply warp",result.isSuccess&&!s.busy,"Apply warp"){
        focus.clearFocus();keyboard?.hide();service.projectEdit{WarpSettings.apply(it,slot,enabled,tempo!!,frame!!,shift!!)}
    }
    Text("Saved with the project; Undo restores the previous settings. Disabling Warp plays the original speed and pitch. Warp uses the same stereo processing in playback and export. Extreme ratios can soften attacks or add texture; CPU use increases with simultaneous warped tracks.",fontSize=12.sp,color=Muted)
}
