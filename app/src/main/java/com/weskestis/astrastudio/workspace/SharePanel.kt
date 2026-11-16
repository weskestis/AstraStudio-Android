package com.weskestis.astrastudio.workspace

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.audio.StudioState
import com.weskestis.astrastudio.project.FinishedTracks

@Composable internal fun SharePanel(s: StudioState,service: AudioService){
    val context=LocalContext.current
    var rate by rememberSaveable{mutableIntStateOf(48000)}
    var tail by rememberSaveable{mutableStateOf("4")}
    var floating by rememberSaveable{mutableStateOf(false)}
    var visible by rememberSaveable{mutableIntStateOf(20)}
    var savePath by rememberSaveable{mutableStateOf("")}
    var deletePath by rememberSaveable{mutableStateOf("")}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")){uri->if(uri!=null&&savePath.isNotBlank())service.saveFinishedTrack(uri,savePath)}
    Text("FINISHED TRACKS",color=Green,fontSize=18.sp)
    Text("Render the complete Arrangement, including the master, effects and sends. Then share the WAV or save it to a location you choose.",fontSize=13.sp,color=Muted)
    Tools{FilterChip(!floating,{floating=false},label={Text("24-bit WAV")},modifier=Modifier.testTag("Share PCM24"));FilterChip(floating,{floating=true},label={Text("32-bit float")})}
    Tools{listOf(44100,48000).forEach{r->FilterChip(rate==r,{rate=r},label={Text("${r/1000f} kHz")})}}
    NumberField("Effect tail seconds · 0–30",tail,{tail=it})
    Action("Render finished track",!s.busy&&s.project.regions.isNotEmpty()&&tail.toFloatOrNull()?.let{it.isFinite()&&it in 0f..30f}==true){service.renderFinishedTrack(rate,tail.toFloat(),floating)}
    if(s.project.regions.isEmpty())Text("Place clips in Arrangement before rendering.",color=Green)
    Text("SoundCloud: save the WAV, then choose it from SoundCloud’s Upload screen if SoundCloud is absent from the share sheet. Email and messaging apps apply their own attachment limits. Nothing is uploaded until you choose a destination.",fontSize=12.sp,color=Muted)
    Text("Exports stay here until you delete them. A new render captures your current edits; previously rendered files stay unchanged.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp))
    if(s.finishedExports.isEmpty())Text("No finished tracks yet.",modifier=Modifier.testTag("No finished tracks"))
    s.finishedExports.take(visible).forEachIndexed{i,e->
        HorizontalDivider(Modifier.padding(vertical=10.dp))
        Text(e.name,color=Green,modifier=Modifier.testTag("Finished track $i"))
        Text("${number(e.bytes/1048576.0)} MiB",fontSize=12.sp,color=Muted)
        Tools{
            Action("Share WAV",!s.busy,"Share WAV $i"){
                runCatching{context.startActivity(Intent.createChooser(FinishedTracks(context).sendIntent(e.path),"Share finished track"))}
                    .onFailure{service.notice(it.message?:"No compatible sharing app is available. Save the WAV instead.")}
            }
            Action("Save WAV as…",!s.busy,"Save finished $i"){savePath=e.path;save.launch(e.name)}
            Action("Delete export",!s.busy,"Delete finished $i"){deletePath=e.path}
        }
    }
    if(s.finishedExports.size>visible)Action("Show more finished tracks"){visible+=20}
    if(deletePath.isNotEmpty())AlertDialog(onDismissRequest={deletePath=""},title={Text("Delete this finished WAV?")},text={Text("The project and recordings are kept. Apps still reading this shared file may lose access.")},
        confirmButton={TextButton({service.deleteFinishedTrack(deletePath);deletePath=""}){Text("Delete")}},dismissButton={TextButton({deletePath=""}){Text("Keep")}})
}
