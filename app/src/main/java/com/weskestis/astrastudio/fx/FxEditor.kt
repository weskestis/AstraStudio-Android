package com.weskestis.astrastudio.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.audio.StudioState
import java.util.Locale
import kotlin.math.*

@Composable
fun FxEditor(track: Int, state: StudioState, service: AudioService, initialUnit: Int = 0) {
    val config = state.effects[track]
    var unit by rememberSaveable(track,initialUnit) { mutableIntStateOf(initialUnit.coerceIn(0,7)) }
    var macro by rememberSaveable { mutableIntStateOf(0) }
    var masterSpectrum by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    com.weskestis.astrastudio.workspace.PanelBackHandler(expanded){expanded=false}
    val set: (Int,Float)->Unit = { id,v -> service.fxParameter(track,id,v) }
    LaunchedEffect(track,masterSpectrum) { service.meterTrack(if(masterSpectrum)16 else track) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EFFECTS / TRACK ${track+1}", fontWeight = FontWeight.Bold)
            Text(config.name, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FxSchema.presets.forEachIndexed { i,name -> OutlinedButton(onClick={service.fxPreset(track,i)}) { Text(name) } }
            }
            Choices("Routing",config[FxSchema.RackMode].toInt(),listOf("Serial","Parallel A/B")){set(FxSchema.RackMode,it.toFloat())}
            if(config[FxSchema.RackMode]!=0f) Param(config,FxSchema.RackBalance,set)
            Param(config,FxSchema.RackMix,set)
            Param(config,FxSchema.RackOutput,set)
            Choices("Spectrum source",if(masterSpectrum)1 else 0,listOf("This track","Master")){masterSpectrum=it==1}
            SpectrumDisplay(config,state.fxStats,if(masterSpectrum)16 else track,!masterSpectrum)
            Text("Spectrum −100 to 0 dBFS • EQ curve ±24 dB • 20 Hz–20 kHz",fontSize=11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                config.order.forEach { id ->
                    FilterChip(unit==id,{unit=id;expanded=true},label={
                        Text("${if(config[FxConfig.enabled[id]]!=0f)"●" else "○"} ${FxSchema.units[id]}${if(config[FxSchema.RackMode]!=0f)" · ${if(config.lane(id)==0)"A" else "B"}" else ""}")
                    })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(config[FxConfig.enabled[unit]]!=0f,{set(FxConfig.enabled[unit],if(config[FxConfig.enabled[unit]]==0f)1f else 0f)},label={Text("${FxSchema.units[unit]} on")})
                OutlinedButton(onClick={service.fxMove(track,unit,-1)},enabled=config.order.first()!=unit){Text("Move left")}
                OutlinedButton(onClick={service.fxMove(track,unit,1)},enabled=config.order.last()!=unit){Text("Move right")}
                OutlinedButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide controls" else "Show controls")}
            }
            if(config[FxSchema.RackMode]!=0f) Choices("Lane",config.lane(unit),listOf("A","B")){service.fxLane(track,unit,it)}
            if(expanded) {
                when(unit) {
                    0 -> EqControls(config,set)
                    1 -> {
                        Text("Three-band upward / downward dynamics",fontWeight=FontWeight.Bold)
                        listOf(FxSchema.OttDepth,FxSchema.OttUp,FxSchema.OttDown,FxSchema.OttUpRatio,FxSchema.OttDownRatio,
                            FxSchema.OttAttack,FxSchema.OttRelease,FxSchema.OttLow,FxSchema.OttHigh).forEach{Param(config,it,set)}
                        repeat(3){band->
                            Text("${listOf("LOW","MID","HIGH")[band]}  ${number(state.fxStats.bands[track*3+band])} dB",color=MaterialTheme.colorScheme.primary)
                            repeat(3){Param(config,FxSchema.Ott1Lower+band*3+it,set)}
                        }
                    }
                    2 -> {
                        Choices("Shape",config[FxSchema.ShaperMode].toInt(),listOf("Soft clip","Hard clip","Sine fold","Tube")){set(FxSchema.ShaperMode,it.toFloat())}
                        Text("2× oversampled waveshaping",fontSize=12.sp)
                        listOf(FxSchema.ShaperPre,FxSchema.ShaperPost,FxSchema.ShaperMix).forEach{Param(config,it,set)}
                    }
                    3 -> {
                        Choices("Detector source",config[FxSchema.ScSource].toInt()+1,listOf("Self")+(1..16).map{"Track $it"}){set(FxSchema.ScSource,(it-1).toFloat())}
                        Text("External tracks trigger before their effects, volume and mute. Play a phrase on that track, then audition this one.",fontSize=12.sp)
                        ReductionHistory(track,state.fxStats.sidechain[track],state.stats.frame)
                        listOf(FxSchema.ScThreshold,FxSchema.ScRatio,FxSchema.ScAttack,FxSchema.ScRelease,FxSchema.ScKnee,FxSchema.ScHighpass,FxSchema.ScMakeup,FxSchema.ScMix).forEach{Param(config,it,set)}
                    }
                    4 -> {
                        Choices("Delay clock",config[FxSchema.DelaySync].toInt(),listOf("Milliseconds","Tempo sync")){set(FxSchema.DelaySync,it.toFloat())}
                        if(config[FxSchema.DelaySync]!=0f) {
                            val beats=listOf(.125f,.25f,1f/3,.5f,1f,2f)
                            Choices("Beats",beats.indexOfFirst{abs(it-config[FxSchema.DelayBeats])<.001f},listOf("1/32","1/16","1/8 triplet","1/8","1/4","1/2")){set(FxSchema.DelayBeats,beats[it])}
                        } else Param(config,FxSchema.DelayMs,set)
                        listOf(FxSchema.DelayFeedback,FxSchema.DelayTone,FxSchema.DelayMix).forEach{Param(config,it,set)}
                    }
                    5 -> {
                        FilterChip(config[FxSchema.ReverbFreeze]!=0f,{set(FxSchema.ReverbFreeze,if(config[FxSchema.ReverbFreeze]==0f)1f else 0f)},label={Text("Freeze tail")})
                        listOf(FxSchema.ReverbSize,FxSchema.ReverbDecay,FxSchema.ReverbDamping,FxSchema.ReverbWash,FxSchema.ReverbMix).forEach{Param(config,it,set)}
                    }
                    6 -> listOf(FxSchema.ChorusRate,FxSchema.ChorusDepth,FxSchema.ChorusFeedback,FxSchema.ChorusMix).forEach{Param(config,it,set)}
                    7 -> {
                        Choices("Modulation",config[FxSchema.ModMode].toInt(),listOf("Phaser","Flanger")){set(FxSchema.ModMode,it.toFloat())}
                        listOf(FxSchema.ModRate,FxSchema.ModDepth,FxSchema.ModFeedback,FxSchema.ModMix).forEach{Param(config,it,set)}
                    }
                }
            }
            HorizontalDivider()
            Choices("Rack macros",macro,(1..4).map{"Macro $it"}){macro=it}
            val at=config.macroOffset(macro)
            Choices("Destination",config.wire[at].toInt(),listOf("Off")+FxConfig.macroTargets.map{FxSchema.parameters[it].name}){service.fxMacro(track,macro,0,it.toFloat())}
            ValueSlider("Depth",config.wire[at+1],-1f..1f){service.fxMacro(track,macro,1,it)}
            ValueSlider("Macro ${macro+1}",config.wire[at+2],0f..1f){service.fxMacro(track,macro,2,it)}
        }
    }
}

@Composable
private fun EqControls(config: FxConfig, set: (Int,Float)->Unit) {
    var band by rememberSaveable{mutableIntStateOf(0)}
    Choices("EQ mode",config[FxSchema.EqMs].toInt(),listOf("Stereo","Mid / Side")){set(FxSchema.EqMs,it.toFloat())}
    Choices("EQ band",band,(1..8).map{"Band $it"}){band=it}
    val at=FxSchema.Eq1On+band*6
    FilterChip(config[at]!=0f,{set(at,if(config[at]==0f)1f else 0f)},label={Text("Band ${band+1} enabled")})
    Choices("Filter type",config[at+1].toInt(),listOf("Peak","Low shelf","High shelf","Low pass","High pass","Notch")){set(at+1,it.toFloat())}
    if(config[FxSchema.EqMs]!=0f) Choices("Process",config[at+5].toInt(),listOf("Both","Mid","Side")){set(at+5,it.toFloat())}
    (2..4).forEach{Param(config,at+it,set)}
}

@Composable
private fun Choices(label: String, selected: Int, choices: List<String>, choose: (Int)->Unit) {
    Text(label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        choices.forEachIndexed{i,name->FilterChip(selected==i,{choose(i)},label={Text(name)},modifier=Modifier.heightIn(min=48.dp))}
    }
}

private fun number(value: Float)=String.format(Locale.US,if(abs(value)>=100)"%.0f" else "%.2f",value)

@Composable
private fun Param(config: FxConfig, id: Int, set: (Int,Float)->Unit) {
    val spec=FxSchema.parameters[id]
    ValueSlider(spec.name,config[id],spec.minimum..spec.maximum,spec.logarithmic){set(id,it)}
}

@Composable
fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, logarithmic: Boolean=false, change: (Float)->Unit) {
    Text("$label  ${number(value)}",fontSize=12.sp)
    val normalized=if(logarithmic)ln(value/range.start)/ln(range.endInclusive/range.start) else (value-range.start)/(range.endInclusive-range.start)
    Slider(normalized.coerceIn(0f,1f),{v->change(if(logarithmic)range.start*(range.endInclusive/range.start).pow(v) else range.start+v*(range.endInclusive-range.start))},
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription=label})
}

@Composable
private fun SpectrumDisplay(config: FxConfig, stats: FxStats, source: Int, showEq: Boolean) {
    val rate=stats.sampleRate.toDouble().coerceAtLeast(8000.0)
    val maximum=min(20000.0,rate*.475)
    val curves=remember(config,rate,showEq){(1..2).map{channel->List(192){i->
        if(showEq)EqResponse.db(config,20.0*(maximum/20).pow(i/191.0),rate,channel) else 0f
    }}}
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics{contentDescription="Live spectrum. Green EQ curve for stereo or mid, lime for side."}) {
        drawRect(Color(0xFF030805))
        for(y in 0..4)drawLine(Color(0xFF173F23),Offset(0f,size.height*y/4),Offset(size.width,size.height*y/4))
        val width=size.width/64
        if(stats.meterTrack==source)stats.spectrum.forEachIndexed{i,db->val h=size.height*((db+100)/100).coerceIn(0f,1f)
            drawRect(Color(0xFF268342),Offset(i*width,size.height-h),Size((width-1).coerceAtLeast(1f),h))}
        if(showEq)curves.forEachIndexed{channel,values->
            if(channel==0||config[FxSchema.EqMs]!=0f){val path=Path();values.forEachIndexed{i,db->
                val x=size.width*i/(values.size-1);val y=size.height*(.5f-db.coerceIn(-24f,24f)/48)
                if(i==0)path.moveTo(x,y) else path.lineTo(x,y)}
                drawPath(path,if(channel==0)Color(0xFF55F27A) else Color(0xFFB6F56B),style=Stroke(2.dp.toPx()))}
        }
    }
}

@Composable
private fun ReductionHistory(track: Int, reduction: Float, frame: Long) {
    var history by remember(track){mutableStateOf(List(120){0f})}
    LaunchedEffect(frame,track){history=if(frame==0L)List(120){0f} else history.drop(1)+reduction}
    Text("Gain reduction  ${number(reduction)} dB • last 6 seconds",color=MaterialTheme.colorScheme.primary,fontSize=12.sp)
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics{contentDescription="Sidechain gain reduction history, zero to 36 decibels"}) {
        drawRect(Color(0xFF030805));val path=Path()
        history.forEachIndexed{i,v->val x=size.width*i/(history.size-1);val y=size.height*(v/36).coerceIn(0f,1f)
            if(i==0)path.moveTo(x,y) else path.lineTo(x,y)}
        drawPath(path,Color(0xFF55F27A),style=Stroke(2.dp.toPx()))
    }
}
