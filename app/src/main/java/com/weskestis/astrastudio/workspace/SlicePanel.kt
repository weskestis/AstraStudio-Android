package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.fx.ValueSlider
import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.recording.WaveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.*

@Composable internal fun SlicePanel(s: StudioState,slot: Int,service: AudioService){
    var selected by rememberSaveable{mutableIntStateOf(-1)}
    var query by rememberSaveable{mutableStateOf("")}
    val capture by service.recorder.state.collectAsState()
    LaunchedEffect(s.sliceAsset,s.project.assets.size){if(s.sliceAsset>=0&&s.project.asset(s.sliceAsset)!=null)selected=s.sliceAsset}
    PanelBackHandler(selected>=0){selected=-1;service.stopSelectionPreview()}
    DisposableEffect(Unit){onDispose{service.stopSelectionPreview()}}
    val asset=s.project.asset(selected)
    Text("SLICE TO PADS",color=Green,modifier=Modifier.testTag("Slice panel"))
    Text(s.message,fontSize=12.sp,color=Muted)
    if(asset==null){
        Text("Choose a project sample or a saved recording. Originals stay intact; markers are stored in the project.",fontSize=13.sp)
        val keyboard=LocalSoftwareKeyboardController.current
        OutlinedTextField(query,{query=it},singleLine=true,label={Text("Find a source")},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),modifier=Modifier.fillMaxWidth().testTag("Slice source search"))
        s.project.assets.filter{it.name.contains(query,true)}.forEach{a->OutlinedButton({selected=a.id},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("Slice source ${a.id}")){Text(a.name)}}
        if(capture.takes.isNotEmpty())Text("SAVED RECORDINGS",color=Green,modifier=Modifier.padding(top=12.dp))
        capture.takes.filter{it.name.contains(query,true)}.forEach{take->OutlinedButton({service.recordingForSlices(take.path)},enabled=!s.busy&&!capture.active,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("Slice recording")){Text(take.name+" · copy into project")}}
        if(s.project.assets.isEmpty()&&capture.takes.isEmpty())Text("Record in Record & Sample Lab, or import a file in Samples first.")
        return
    }
    val markers=asset.slices.ifEmpty{listOf(0L,asset.frames)}
    var cursor by rememberSaveable(asset.id){mutableLongStateOf(asset.frames/2)}
    var selectedMarker by rememberSaveable(asset.id){mutableIntStateOf(-1)}
    var position by rememberSaveable(asset.id){mutableStateOf(cursor.toString())}
    var zoom by rememberSaveable(asset.id){mutableFloatStateOf(1f)}
    var pan by rememberSaveable(asset.id){mutableFloatStateOf(0f)}
    var count by rememberSaveable{mutableIntStateOf(16)}
    var sensitivity by rememberSaveable{mutableFloatStateOf(.65f)}
    val span=max(1L,(asset.frames/zoom).toLong());val first=((asset.frames-span)*pan).toLong();val last=min(asset.frames,first+span)
    var envelope by remember(asset.id){mutableStateOf(asset.waveform)}
    var waveError by remember(asset.id){mutableStateOf("")}
    LaunchedEffect(asset.id,first,last){val job=coroutineContext
        try{envelope=withContext(Dispatchers.IO){WaveFile(File(service.samplePath(asset))).use{it.envelope(first,last,512){check(job.isActive)}}}.toList();waveError=""}
        catch(e: Exception){if(job.isActive)waveError=e.message?:"Could not read waveform."}}
    Text(asset.name,color=Green,modifier=Modifier.padding(vertical=8.dp).testTag("Slice source title"))
    Canvas(Modifier.fillMaxWidth().height(156.dp).background(Color.Black).testTag("Slice waveform").pointerInput(first,last){detectTapGestures{point->cursor=(first+(point.x/size.width).coerceIn(0f,1f)*span).toLong().coerceIn(0,asset.frames);position=cursor.toString();selectedMarker=-1}}){
        for(i in 0 until min(512,envelope.size/2)){val x=size.width*i/512;drawLine(Green,Offset(x,size.height*(.5f-envelope[2*i].coerceIn(-1f,1f)*.45f)),Offset(x,size.height*(.5f-envelope[2*i+1].coerceIn(-1f,1f)*.45f)),1f)}
        fun line(frame: Long,color: Color,width: Float){if(frame in first..last){val x=((frame-first).toDouble()/span*size.width).toFloat();drawLine(color,Offset(x,0f),Offset(x,size.height),width)}}
        markers.forEachIndexed{i,m->line(m,if(i==selectedMarker)Color.Yellow else Green,2f)};line(cursor,Color.White,3f)
    }
    if(waveError.isNotEmpty())Text(waveError,color=Color.Yellow)
    Text("${number(first.toDouble()/asset.rate)}–${number(last.toDouble()/asset.rate)} s · ${markers.size-1}/16 slices. Tap the waveform to position the white cursor.",fontSize=12.sp,color=Muted)
    ValueSlider("Waveform zoom",zoom,1f..1024f,true,change={zoom=it})
    if(zoom>1)ValueSlider("Waveform position",pan,0f..1f,change={pan=it})
    Tools{listOf(4,8,16).forEach{n->FilterChip(count==n,{count=n},label={Text("$n slices")})}}
    ValueSlider("Transient sensitivity",sensitivity,0f..1f,change={sensitivity=it})
    Tools{Action("Detect transients",!s.busy,"Detect slices"){service.detectSlices(asset.id,count,sensitivity)};Action("Equal slices",!s.busy,"Equal slices"){service.sliceMarkers(asset.id,Slicing.equal(asset.frames,count));selectedMarker=-1};Action("Clear markers",!s.busy){service.sliceMarkers(asset.id,emptyList());selectedMarker=-1}}
    Tools{markers.forEachIndexed{i,m->FilterChip(selectedMarker==i,{selectedMarker=i;cursor=m;position=m.toString()},label={Text(if(i==markers.lastIndex)"End"else "${i+1}")},modifier=Modifier.testTag("Slice marker $i"))}}
    NumberField("Marker position · source frame",position,{position=it;it.toLongOrNull()?.takeIf{n->n in 0..asset.frames}?.let{n->cursor=n}})
    val value=position.toLongOrNull();val interior=selectedMarker in 1 until markers.lastIndex
    Tools{
        Action("Add marker",!s.busy&&value!=null&&value in 1 until asset.frames&&value !in markers&&markers.size<17,"Add slice marker"){service.sliceMarkers(asset.id,Slicing.add(markers,asset.frames,value!!));selectedMarker=-1}
        Action("Move marker",!s.busy&&interior&&value!=null&&value>markers.getOrElse(selectedMarker-1){0}&&value<markers.getOrElse(selectedMarker+1){0},"Move slice marker"){service.sliceMarkers(asset.id,Slicing.move(markers,selectedMarker,value!!))}
        Action("Remove marker",!s.busy&&interior,"Remove slice marker"){service.sliceMarkers(asset.id,Slicing.remove(markers,selectedMarker));selectedMarker=-1}
    }
    val slice=(if(selectedMarker in 0 until markers.lastIndex)selectedMarker else markers.indexOfLast{it<=cursor}.coerceAtLeast(0)).coerceAtMost(markers.lastIndex-1)
    Text("Slice ${slice+1}: frames ${markers[slice]}–${markers[slice+1]} · MIDI ${36+slice}",fontSize=12.sp,color=Muted)
    Tools{Action("Preview slice",!s.busy&&!capture.active,"Preview slice"){service.previewSelection(service.samplePath(asset),markers[slice],markers[slice+1])};Action("Stop preview"){service.stopSelectionPreview()}}
    Text("Replaces Track ${slot%ProjectLimits.TRACKS+1}'s pad assignments. Each pad references this source with editable trim and short fades. Undo restores the previous kit.",fontSize=13.sp)
    Action("Send slices to pads",!s.busy,"Apply slices"){if(asset.slices.isEmpty())service.sliceMarkers(asset.id,markers);service.applySlices(asset.id,slot,false)}
        Action("Pads + MIDI clip",!s.busy,"Slices with MIDI"){if(asset.slices.isEmpty())service.sliceMarkers(asset.id,markers);service.applySlices(asset.id,slot,true)}
    Text("The MIDI clip follows source timing at the current BPM. One-shot slices play their full range; changing BPM changes their trigger timing, not their audio duration.",fontSize=12.sp,color=Muted)
}

@Composable internal fun InstrumentLibraryPanel(s: StudioState,track: Int,service: AudioService){
    var name by rememberSaveable{mutableStateOf("")};var query by rememberSaveable{mutableStateOf("")};var deleting by rememberSaveable{mutableStateOf("")}
    val keyboard=LocalSoftwareKeyboardController.current
    PanelBackHandler(deleting.isNotEmpty()){deleting=""}
    Text("SAVED INSTRUMENTS · ${s.instrumentPresets.size}/256",color=Green,modifier=Modifier.testTag("Instrument library"))
    Text("Save a sampler or full 16-pad kit with all layers, trim, tuning, envelopes, fades, loops and insert effects. Independent audio copies keep it available across projects.",fontSize=13.sp)
    OutlinedTextField(name,{name=it.take(64)},singleLine=true,label={Text("Instrument name")},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}),modifier=Modifier.fillMaxWidth().testTag("Instrument preset name"))
    Action("Save current instrument",!s.busy&&name.isNotBlank()&&s.project.instruments[track].mode in 1..2&&s.project.instruments[track].assets().isNotEmpty(),"Save instrument"){keyboard?.hide();service.saveInstrument(name,track)}
    Text(s.message,fontSize=12.sp,color=Green)
    OutlinedTextField(query,{query=it},singleLine=true,label={Text("Search saved instruments")},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),modifier=Modifier.fillMaxWidth())
    s.instrumentPresets.filter{it.name.contains(query,true)}.forEach{preset->
        Text(preset.name,color=Green,modifier=Modifier.padding(top=14.dp))
        Text("${if(preset.mode==2)"Drum rack"else "Sampler"} · ${preset.sounds} sources · ${number(preset.bytes/1048576.0)} MiB",fontSize=12.sp,color=Muted)
        Tools{Action("Load on Track ${track+1}",!s.busy,"Load instrument ${preset.id}"){service.loadInstrument(preset.id,track)};Action("Delete saved copy",!s.busy){deleting=preset.id}}
        if(deleting==preset.id){Text("Delete this saved preset and its private audio? Instruments already loaded in projects are kept.");Tools{Action("Delete permanently",!s.busy){service.deleteInstrument(preset.id);deleting=""};Action("Keep it"){deleting=""}}}
    }
    Text("Loading replaces this track's instrument and insert effects. Its mixer, sends and clips stay in place; Undo restores the previous settings. Presets stay on this device. Save an .astra project to transfer a loaded kit with its audio.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=16.dp))
}
