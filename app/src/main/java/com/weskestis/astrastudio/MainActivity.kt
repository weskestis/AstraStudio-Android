package com.weskestis.astrastudio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.audio.AudioStats
import com.weskestis.astrastudio.audio.StudioState
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var audioService by mutableStateOf<AudioService?>(null)
    private var bound = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginAudio()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            audioService = (binder as AudioService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { audioService = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF76E1BC), onPrimary = Color(0xFF09271F),
                background = Color(0xFF11151C), surface = Color(0xFF1C222C),
                secondary = Color(0xFFB5B0FF))) {
                Surface(Modifier.fillMaxSize()) {
                    val service = audioService
                    if (service == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening Astra Studio…")
                    } else {
                        val state by service.state.collectAsStateWithLifecycle()
                        StudioScreen(state, service, ::requestAudio)
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, AudioService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        audioService?.releaseTouchNotes()
        if (bound) { unbindService(connection); bound = false }
        audioService = null
        super.onStop()
    }
    private fun requestAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else beginAudio()
    }
    private fun beginAudio() {
        ContextCompat.startForegroundService(this, Intent(this, AudioService::class.java).setAction(AudioService.ACTION_START))
    }
}

@Composable
private fun StudioScreen(state: StudioState, service: AudioService, start: () -> Unit) {
    var selectedTrack by rememberSaveable { mutableIntStateOf(0) }
    val track = state.tracks[selectedTrack]
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ASTRA / STUDIO", fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 2.sp)
                Text("01   AUDIO FOUNDATION", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 2.sp)
            }
            Text("ANDROID 14+", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (state.stats.running) service.stopFromUi() else start() }) {
                Text(if (state.stats.running) "Stop audio" else "Start audio")
            }
            OutlinedButton(onClick = service::transport, enabled = state.stats.running) {
                Text(if (state.stats.playing) "Stop transport" else "Play transport")
            }
            OutlinedButton(onClick = service::demo, enabled = state.stats.running) { Text("Timing demo") }
            OutlinedButton(onClick = service::panic, enabled = state.stats.running) { Text("Panic") }
        }
        Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TEMPO  ${state.bpm.toInt()}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("4/4", modifier = Modifier.padding(horizontal = 12.dp))
                    OutlinedButton(onClick = service::tapTempo) { Text("Tap") }
                }
                Slider(value = state.bpm, onValueChange = service::tempo, valueRange = 30f..300f,
                    modifier = Modifier.semantics { contentDescription = "Tempo in beats per minute" })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("House" to 128f, "Trance" to 138f, "Riddim" to 145f).forEach { (label, bpm) ->
                        FilterChip(selected = state.bpm.toInt() == bpm.toInt(), onClick = { service.tempo(bpm) }, label = { Text("$label ${bpm.toInt()}") })
                    }
                    FilterChip(selected = state.metronome, onClick = { service.metronome(!state.metronome) }, label = { Text("Click") })
                }
                LevelMeter(state.stats)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tracks.indices.forEach { index ->
                FilterChip(selected = index == selectedTrack, onClick = { selectedTrack = index },
                    label = { Text("Track ${index + 1}") })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRACK ${selectedTrack + 1}  /  TEST INSTRUMENT", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Hold pads to play • F minor • basic sine voice", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val notes = listOf("F" to 41, "G" to 43, "A♭" to 44, "B♭" to 46, "C" to 48, "D♭" to 49, "E♭" to 51, "F⁺" to 53)
                    notes.forEachIndexed { index, (label, note) ->
                        NotePad(label, selectedTrack, index, state.stats.running,
                            down = { service.noteOn(selectedTrack, note, 1 + selectedTrack * 8 + index) },
                            up = { service.noteOff(selectedTrack, 1 + selectedTrack * 8 + index) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", modifier = Modifier.width(70.dp))
                    Slider(track.gain, { service.track(selectedTrack, track.copy(gain = it)) }, valueRange = 0f..2f,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Track volume" })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pan", modifier = Modifier.width(70.dp))
                    Slider(track.pan, { service.track(selectedTrack, track.copy(pan = it)) }, valueRange = -1f..1f,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Track pan" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(track.mute, { service.track(selectedTrack, track.copy(mute = !track.mute)) }, label = { Text("Mute") })
                    FilterChip(track.solo, { service.track(selectedTrack, track.copy(solo = !track.solo)) }, label = { Text("Solo") })
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MASTER / ENGINE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Slider(state.master, service::master, valueRange = 0f..1f,
                    modifier = Modifier.semantics { contentDescription = "Master volume" })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Fast" to 2, "Balanced" to 3, "Stable" to 4, "Large" to 8).forEach { (label, bursts) ->
                        FilterChip(state.bufferBursts == bursts, { service.buffer(bursts) }, label = { Text("$label · $bursts") })
                    }
                }
                val s = state.stats
                Text("${s.sampleRate} Hz  ·  ${if (s.exclusive) "Exclusive" else "Shared / idle"}  ·  ${s.activeVoices}/128 voices", fontSize = 12.sp)
                Text(String.format(Locale.US, "DSP %.1f%%  ·  buffer %.1f ms  ·  xruns %d", s.cpuLoad * 100, s.bufferMs, s.xruns), fontSize = 12.sp)
                Text("Queue drops ${s.droppedCommands}  ·  late events ${s.lateCommands}  ·  clips ${s.clippedSamples}", fontSize = 12.sp)
                Text("MIDI inputs ${state.midiDevices}  ·  channel 1–16 → track 1–16", fontSize = 12.sp)
                Text("Buffer duration is not total latency. Use wired audio when testing live play.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Phase 1: native engine + bridge. Synth design, effects, clip editing and Astra commands follow in separate phases.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NotePad(label: String, track: Int, key: Int, enabled: Boolean, down: () -> Unit, up: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentDown by rememberUpdatedState(down)
    val currentUp by rememberUpdatedState(up)
    val scope = rememberCoroutineScope()
    Box(Modifier.size(64.dp, 88.dp)
        .background(if (pressed) MaterialTheme.colorScheme.primary else Color(0xFF303846), RoundedCornerShape(8.dp))
        .semantics {
            contentDescription = "Play $label on track ${track + 1}"
            role = Role.Button
            onClick {
                if (enabled) scope.launch {
                    val releaseThisNote = currentUp
                    currentDown()
                    try { delay(180) } finally { releaseThisNote() }
                }
                true
            }
        }
        .pointerInput(track, key, enabled) {
            detectTapGestures(onPress = {
                if (enabled) {
                    val releaseThisNote = currentUp
                    pressed = true
                    currentDown()
                    try { awaitRelease() } finally { pressed = false; releaseThisNote() }
                }
            })
        }, contentAlignment = Alignment.Center) {
        Text(label, color = if (pressed) Color(0xFF09271F) else Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LevelMeter(stats: AudioStats) {
    Canvas(Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = "Stereo output peak meter" }) {
        val h = (size.height - 4.dp.toPx()) / 2
        drawRect(Color(0xFF303846), size = size)
        drawRect(Color(0xFF76E1BC), Offset.Zero, Size(size.width * stats.peakLeft.coerceIn(0f, 1f), h))
        drawRect(Color(0xFFB5B0FF), Offset(0f, h + 4.dp.toPx()), Size(size.width * stats.peakRight.coerceIn(0f, 1f), h))
    }
}
