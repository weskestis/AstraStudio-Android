package com.weskestis.astrastudio.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.AudioService
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun SynthEditor(track: Int, patch: SynthPatch, service: AudioService, running: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var audition by remember(track) { mutableStateOf(false) }
    DisposableEffect(track) { onDispose { service.audition(track, false) } }
    LaunchedEffect(running) { if (!running) audition = false }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SYNTH / TRACK ${track + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(patch.name, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SynthSchema.presets.forEachIndexed { i, name ->
                    FilterChip(patch.name == name, { service.preset(track, i) }, label = { Text(name) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = running, onClick = { audition = !audition; service.audition(track, audition) }) {
                    Text(if (audition) "Release F1" else "Hold F1 to edit")
                }
                OutlinedButton(enabled = running, onClick = { service.demo(track) }) { Text("Play phrase") }
            }
            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide synth controls" else "Show synth controls") }
            if (expanded) {
            Choice("Instrument", patch[SynthSchema.Instrument].toInt(), listOf("Wavetable", "4-operator FM")) {
                service.synthParameter(track, SynthSchema.Instrument, it.toFloat())
            }
            Parameter(patch, SynthSchema.Output) { id, value -> service.synthParameter(track, id, value) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Oscillators", "Filters", "Envelopes", "LFOs", "Modulation").forEachIndexed { i, label ->
                    FilterChip(page == i, { page = i }, label = { Text(label) })
                }
            }
            val set: (Int, Float) -> Unit = { id, value -> service.synthParameter(track, id, value) }
            when (page) {
                0 -> if (patch[SynthSchema.Instrument] == 0f) WavetableControls(patch, set) else FmControls(patch, set)
                1 -> FilterControls(patch, set)
                2 -> EnvelopeControls(patch, set)
                3 -> LfoControls(track, patch, service, set)
                4 -> ModulationControls(track, patch, service)
            }
            }
            Text("Changes are saved on this phone. Patch output feeds the track mixer.", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WavetableControls(patch: SynthPatch, set: (Int, Float) -> Unit) {
    var osc by rememberSaveable { mutableIntStateOf(0) }
    Tabs(listOf("Osc A", "Osc B"), osc) { osc = it }
    val base = SynthSchema.OscABank + osc * 7
    Choice("Bank", patch[base].toInt(), listOf("Basic shapes", "Digital", "Monster growls", "Metallic formants")) { set(base, it.toFloat()) }
    for (id in base + 1..base + 4) Parameter(patch, id, set)
    Choice("Warp", patch[base + 5].toInt(), listOf("Off", "Sync", "Bend +", "Bend −")) { set(base+5, it.toFloat()) }
    Parameter(patch, base+6, set)
    Parameter(patch, SynthSchema.FmAmount, set)
    Text("A modulates B even when A's mix level is zero. Raise B's level to hear its FM.", fontSize = 11.sp)
    Parameter(patch, SynthSchema.SubLevel, set)
    Parameter(patch, SynthSchema.NoiseLevel, set)
}

@Composable
private fun FmControls(patch: SynthPatch, set: (Int, Float) -> Unit) {
    Choice("Algorithm", patch[SynthSchema.Algorithm].toInt(), listOf("4 → 3 → 2 → 1", "2 → 1 + 4 → 3", "2 + 3 + 4 → 1", "1 + 2 + 3 + 4")) {
        set(SynthSchema.Algorithm, it.toFloat())
    }
    Parameter(patch, SynthSchema.FmIndex, set)
    Parameter(patch, SynthSchema.Feedback, set)
    var op by rememberSaveable { mutableIntStateOf(0) }
    Tabs(listOf("Op 1", "Op 2", "Op 3", "Op 4"), op) { op = it }
    val base = SynthSchema.Op1Ratio + op * 6
    for (id in base until base + 6) Parameter(patch, id, set)
    Parameter(patch, SynthSchema.SubLevel, set)
    Parameter(patch, SynthSchema.NoiseLevel, set)
}

@Composable
private fun FilterControls(patch: SynthPatch, set: (Int, Float) -> Unit) {
    Choice("Routing", patch[SynthSchema.FilterRouting].toInt(), listOf("Serial: 1 → 2", "Parallel: 1 + 2")) { set(SynthSchema.FilterRouting, it.toFloat()) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Tabs(listOf("Filter 1", "Filter 2"), filter) { filter = it }
    val base = SynthSchema.Filter1Type + filter * 5
    Choice("Mode", patch[base].toInt(), listOf("Bypass", "Low-pass", "High-pass", "Band-pass", "Comb", "Vowel")) { set(base, it.toFloat()) }
    for (id in base+1..base+4) Parameter(patch, id, set)
    Text("Cutoff tunes the comb's delay or scales the vowel formants. Vowel moves through A / E / I / O / U. The sub bypasses both filters.", fontSize = 11.sp)
}

@Composable
private fun EnvelopeControls(patch: SynthPatch, set: (Int, Float) -> Unit) {
    var env by rememberSaveable { mutableIntStateOf(0) }
    Tabs(listOf("1 · Amp", "2 · Mod", "3 · Mod"), env) { env = it }
    for (id in SynthSchema.Env1Attack + env*4 until SynthSchema.Env1Attack + (env+1)*4) Parameter(patch, id, set)
    Text("Envelope 1 controls the final voice level. All three envelopes are available in the modulation matrix.", fontSize = 11.sp)
}

@Composable
private fun LfoControls(track: Int, patch: SynthPatch, service: AudioService, set: (Int, Float) -> Unit) {
    var lfo by rememberSaveable { mutableIntStateOf(0) }
    Tabs(listOf("LFO 1", "LFO 2", "LFO 3", "LFO 4"), lfo) { lfo = it }
    val base = SynthSchema.Lfo1Rate + lfo*4
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(patch[base+2] != 0f, { set(base+2, if (patch[base+2] == 0f) 1f else 0f) }, label = { Text("Tempo sync") })
        FilterChip(patch[base+3] != 0f, { set(base+3, if (patch[base+3] == 0f) 1f else 0f) }, label = { Text("Note retrigger") })
    }
    if (patch[base+2] != 0f) {
        val beats = listOf(4f, 2f, 1f, .5f, .25f, 1f/3f, .125f)
        val labels = listOf("1 bar", "1/2", "1/4", "1/8", "1/16", "1/8 triplet", "1/32")
        Choice("Cycle", beats.indexOf(patch[base+1]).coerceAtLeast(0), labels) { set(base+1, beats[it]) }
    } else Parameter(patch, base, set)
    Text("Draw a bipolar shape (−1 to +1). Assign it to a destination in Modulation.", fontSize = 12.sp)
    LfoCanvas(track, lfo, patch.shapePoints(lfo)) { a, av, b, bv -> service.paintLfo(track, lfo, a, av, b, bv) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Sine", "Fall", "Rise", "Pulse").forEachIndexed { i, label ->
            OutlinedButton(onClick = { service.synthShape(track, lfo, i) }) { Text(label) }
        }
    }
}

@Composable
private fun LfoCanvas(track: Int, lfo: Int, points: List<Float>, paint: (Int, Float, Int, Float) -> Unit) {
    val currentPaint by rememberUpdatedState(paint)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "Draw LFO ${lfo + 1} shape; shape preset buttons are below" }
        .pointerInput(track, lfo) {
            var previous = 0; var value = 0f
            fun at(point: Offset): Pair<Int, Float> =
                (point.x / size.width * 31).roundToInt().coerceIn(0,31) to (1f - 2f * point.y / size.height).coerceIn(-1f,1f)
            detectDragGestures(onDragStart = { pos ->
                val (i, v) = at(pos); previous = i; value = v; currentPaint(i, v, i, v)
            }, onDrag = { change, _ ->
                change.consume(); val (i, v) = at(change.position)
                currentPaint(previous, value, i, v); previous = i; value = v
            })
        }) {
        drawRect(Color(0xFF030805))
        for (i in 0..4) drawLine(Color(0xFF173F23), Offset(size.width*i/4, 0f), Offset(size.width*i/4, size.height))
        drawLine(Color(0xFF3B6547), Offset(0f,size.height/2), Offset(size.width,size.height/2))
        val path = Path()
        points.forEachIndexed { i, value ->
            val x = size.width * i / 31; val y = size.height * (1-value) / 2
            if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        drawPath(path, Color(0xFF55F27A), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun ModulationControls(track: Int, patch: SynthPatch, service: AudioService) {
    var slot by rememberSaveable { mutableIntStateOf(0) }
    val sources = listOf("Off", "LFO 1", "LFO 2", "LFO 3", "LFO 4", "Env 1", "Env 2", "Env 3", "Velocity", "Key tracking")
    val targets = listOf("A position", "B position", "A → B FM", "Pitch ±24 semitones", "Filter 1 cutoff ±6 oct", "Filter 2 cutoff ±6 oct", "Amplitude",
        "A warp", "B warp", "Filter 1 resonance", "Filter 2 resonance", "FM operator index", "Filter 1 drive", "Filter 2 drive", "Osc A level", "Osc B level")
    Choice("Route", slot, (1..16).map { "Slot $it" }) { slot = it }
    val at = patch.routeOffset(slot)
    Choice("Source", patch.wire[at].toInt(), sources) { service.synthRoute(track, slot, 0, it.toFloat()) }
    Choice("Destination", patch.wire[at+1].toInt(), targets) { service.synthRoute(track, slot, 1, it.toFloat()) }
    Text(String.format(Locale.US, "Depth %+.2f", patch.wire[at+2]))
    Slider(patch.wire[at+2], { service.synthRoute(track, slot, 2, it) }, valueRange = -1f..1f,
        modifier = Modifier.semantics { contentDescription = "Modulation depth" })
    Text("Active routes", fontWeight = FontWeight.Bold)
    var active = 0
    repeat(16) { i ->
        val r = patch.routeOffset(i)
        if (patch.wire[r] != 0f && patch.wire[r+2] != 0f) {
            active++
            Text("${i+1}. ${sources[patch.wire[r].toInt()]} → ${targets[patch.wire[r+1].toInt()]}  ${String.format(Locale.US, "%+.2f", patch.wire[r+2])}", fontSize = 12.sp)
        }
    }
    if (active == 0) Text("No active modulation routes.", fontSize = 12.sp)
}

@Composable
private fun Parameter(patch: SynthPatch, id: Int, set: (Int, Float) -> Unit) {
    val info = SynthSchema.parameters[id]; val value = patch[id]
    val label = when { info.integer -> value.toInt().toString(); value >= 100 -> String.format(Locale.US, "%.0f", value)
        else -> String.format(Locale.US, "%.3f", value) }
    Text("${info.name}  $label", fontSize = 12.sp)
    val low = if (info.logarithmic) ln(info.minimum) else info.minimum
    val high = if (info.logarithmic) ln(info.maximum) else info.maximum
    val position = if (info.logarithmic) ln(value) else value
    Slider(value = ((position-low)/(high-low)).coerceIn(0f,1f), onValueChange = {
        val v = low + (high-low)*it
        set(id, if (info.logarithmic) exp(v) else v)
    }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = info.name })
}

@Composable
private fun Tabs(names: List<String>, selected: Int, select: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, label -> FilterChip(i == selected, { select(i) }, label = { Text(label) }) }
    }
}

@Composable
private fun Choice(label: String, value: Int, options: List<String>, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label · ${options[value.coerceIn(options.indices)]}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, name -> DropdownMenuItem(text = { Text(name) }, onClick = { select(i); expanded = false }) }
        }
    }
}
