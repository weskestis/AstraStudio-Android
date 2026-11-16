package com.weskestis.astrastudio.workspace

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.project.*
import java.text.DateFormat
import java.util.Date

@Composable internal fun FreezePanel(s: StudioState,track: Int,service: AudioService){
    var rate by rememberSaveable{mutableIntStateOf(192000)}
    var tail by rememberSaveable{mutableStateOf("10")}
    val frozen=s.project.frozen.firstOrNull{it.track==track}
    Text("ARRANGEMENT FREEZE · ${trackName(s,track)}",color=Green,fontWeight=FontWeight.Bold)
    Text("Reduce Arrangement processing by rendering this track to audio. Originals stay editable; playback pauses while freezing.",fontSize=13.sp)
    if(frozen!=null){
        val asset=s.project.asset(frozen.asset)!!
        Text("Frozen · ${ExportFormats.label(asset.rate)} / 32-bit float",color=Green,modifier=Modifier.testTag("Frozen status"))
        Action("Unfreeze track",!s.busy,"Unfreeze track"){service.unfreezeTrack(track)}
    }else{
        Tools{FrozenTracks.rates.forEach{r->FilterChip(rate==r,{rate=r},label={Text(ExportFormats.label(r))},modifier=Modifier.testTag("Freeze rate $r"))}}
        NumberField("Instrument tail seconds · 0–30",tail,{tail=it})
        val seconds=tail.toFloatOrNull()?.takeIf{it.isFinite()&&it in 0f..30f}
        val frames=seconds?.let{runCatching{FrozenTracks.frames(ProjectDocument(s.project,s.bpm,s.master,s.limiterDrive,s.limiterRelease,s.tracks,s.patches,s.effects),rate,it)}.getOrNull()}
        Text("32-bit float · about ${number((44+(frames?:0)*8)/1048576.0)} MiB · one sample slot. Higher rates use more storage and render memory.",color=Muted,fontSize=12.sp)
        Action("Freeze full track",!s.busy&&seconds!=null&&s.project.regions.any{it.slot%ProjectLimits.TRACKS==track},"Freeze full track"){service.freezeTrack(track,rate,seconds!!)}
        if(s.project.regions.none{it.slot%ProjectLimits.TRACKS==track})Text("Place clips on this track in Arrangement first.",color=Green)
    }
    Text("Mixer, effects, sidechains and sends stay live. Session uses the original instruments. Source or tempo edits automatically unfreeze affected tracks.",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
    Text("Undo restores the previous freeze state. Portable projects include both sources and frozen audio. A frozen source has the resolution selected here; a later higher-rate export cannot recreate missing detail. Set a tail long enough for your instrument.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=12.dp))
}

@Composable internal fun RecoveryPanel(s: StudioState,service: AudioService){
    Text("PROJECT RECOVERY",color=Green,fontWeight=FontWeight.Bold)
    Text("Three previous autosaves are retained. A damaged current save or unreadable audio triggers recovery from the newest usable copy at startup. Audio files stay in private storage for Undo and recovery.",fontSize=13.sp)
    if(s.autosavedAt>0)Text("Last autosave: ${DateFormat.getDateTimeInstance().format(Date(s.autosavedAt))}",color=Muted,fontSize=12.sp)
    Action("Refresh recovery copies",!s.busy){service.refreshRecovery()}
    if(s.recovery.isEmpty())Text("No earlier autosaves yet. Copies appear after the project changes and saves.",color=Muted)
    s.recovery.forEachIndexed{i,entry->
        Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){
            Text(entry.name,color=Green)
            Text(DateFormat.getDateTimeInstance().format(Date(entry.savedAt)),fontSize=12.sp)
            Action("Restore copy ${i+1}",!s.busy,"Restore recovery $i"){service.restoreRecovery(entry.token)}
        }
    }
    Text("Restore is undoable. These copies are on this phone; File → Save portable project is still the way to keep a separate backup.",color=Muted,fontSize=12.sp)
}

@Suppress("DEPRECATION")
@Composable internal fun PerformancePanel(s: StudioState,service: AudioService){
    val clipboard=LocalClipboardManager.current;val p=s.performance;val a=s.stats
    val report=buildString {
        appendLine("Astra Studio 0.13.0 · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("50 tracks; ${s.project.frozen.size} frozen Arrangement tracks; ${s.project.assets.size} source assets")
        appendLine("Audio: ${a.sampleRate} Hz; buffer ${number(a.bufferMs)} ms; ${if(a.exclusive)"exclusive" else "shared/idle"}")
        appendLine("DSP current ${number(a.cpuLoad*100)}%; highest polled ${number(p.peakCpu*100)}%; ${p.polls} active polls / ${p.elapsedSeconds}s")
        appendLine("Voices ${a.activeVoices}/128; highest polled ${p.peakVoices}; observed xruns ${p.xruns}; current stream xruns ${a.xruns}")
        appendLine("Queue drops ${a.droppedCommands}; late events ${a.lateCommands}; missed source frames ${s.storage.missedFrames}; read errors ${s.storage.readErrors}")
        appendLine("Java heap ${number(p.javaBytes/1048576.0)} MiB; native heap ${number(p.nativeBytes/1048576.0)} MiB; audio pages ${number(s.storage.audioBytes/1048576.0)} MiB")
    }
    Text("PERFORMANCE",color=Green,fontWeight=FontWeight.Bold)
    Text(report,fontSize=13.sp,modifier=Modifier.testTag("Performance readings"))
    Tools{Action("Reset readings"){service.resetPerformance()};Action("Copy performance report"){clipboard.setText(AnnotatedString(report))}}
    Text("Measurements come from this running device. Polled CPU peaks can miss short spikes; heap totals exclude some graphics/shared memory. Buffer duration is not measured input/output latency.",fontSize=12.sp,color=Muted)
    if(p.xruns>0||a.cpuLoad>.8)Text("Dropouts or high load detected. Freeze demanding Arrangement tracks, increase the Master buffer, or reduce active effects. Export quality is not lowered automatically.",color=Green,modifier=Modifier.testTag("Performance advice"))
    Text("For an Xperia check: play the busiest section for several minutes, then copy this report. Compare live and frozen playback with the same buffer and audio output. Heat and battery endurance need a real-device test.",fontSize=12.sp,color=Muted)
}
