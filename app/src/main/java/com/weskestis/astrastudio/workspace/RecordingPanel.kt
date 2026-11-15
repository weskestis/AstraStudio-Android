package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.recording.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Locale
import kotlin.math.*

private data class LabSource(val path: String,val name: String,val frames: Long,val rate: Int,val take: Boolean,val tag: String)
private data class TakeList(val takes: List<RecordedTake>,val latest: String,val active: Boolean)
private fun time(frames: Long,rate: Int)=String.format(Locale.US,"%.5f s",frames.toDouble()/rate)
private fun db(value: Double)=String.format(Locale.US,"%.1f",20*log10(max(1e-6,value)))

@Composable private fun CaptureControls(service: AudioService,enabled: Boolean,start: (Boolean)->Unit){
    val state by service.recorder.state.collectAsStateWithLifecycle()
    var stereo by rememberSaveable{mutableStateOf(false)}
    Text("RECORD & SAMPLE LAB",color=Green)
    Text("Capture a sound, isolate its bass tone, then play it from your keyboard.",fontSize=12.sp,color=Muted)
    Tools{
        Action(if(state.active)"● Recording" else "● Record microphone",enabled&&!state.active,"Start microphone"){start(stereo)}
        Action("■ Stop & save",state.active&&!state.stopping,"Stop microphone"){service.stopCapture()}
    }
    Tools{FilterChip(!stereo,{stereo=false},enabled=!state.active,label={Text("Mono")});FilterChip(stereo,{stereo=true},enabled=!state.active,label={Text("Stereo if supported")})}
    if(state.active||state.frames>0){
        Text("${time(state.frames,state.rate)} · ${state.rate} Hz · ${state.channels} ch",color=Green,modifier=Modifier.testTag("Capture duration"))
        LinearProgressIndicator(progress={state.peak.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(8.dp),color=if(state.peak>=.999f)Color(0xFFFF735E)else Green)
        Text("Peak ${db(state.peak.toDouble())} dBFS · ${state.clipped} near-clip samples",fontSize=12.sp,color=if(state.clipped>0)Color(0xFFFFA58D)else Muted)
        Text("${state.format}\n${state.route}",fontSize=11.sp,color=Muted)
    }
    Text(state.status,fontSize=12.sp,modifier=Modifier.testTag("Capture status"))
    Text("Input monitoring is off. Back keeps recording; Stop & save finishes the take. Recording is separate from MIDI Record. One take can use up to 1 GiB of storage.",fontSize=11.sp,color=Muted)
}

@Composable internal fun RecordingPanel(s: StudioState,service: AudioService,slot: Int,start: (Boolean)->Unit,importAudio: ()->Unit,save: (String,String,Long,Long)->Unit){
    // Only the small meter subtree consumes the 10 Hz input readings.
    val takesFlow=remember(service){service.recorder.state.map{TakeList(it.takes,it.latest,it.active)}.distinctUntilChanged()}
    val takes by takesFlow.collectAsStateWithLifecycle(TakeList(emptyList(),"",false))
    CaptureControls(service,s.ready&&!s.busy,start)
    Tools{Action("Open audio from storage",!s.busy&&!takes.active,"Lab import"){importAudio()}}
    val sources=remember(takes.takes,s.project.assets){takes.takes.map{LabSource(it.path,it.name,it.frames,it.rate,true,"Lab take "+it.path)}+
        s.project.assets.map{LabSource(service.samplePath(it),it.name,it.frames,it.rate,false,"Lab sample ${it.id}")}}
    var path by rememberSaveable{mutableStateOf("")}
    var choosing by rememberSaveable{mutableStateOf(true)}
    var handledLatest by rememberSaveable{mutableStateOf("")}
    var query by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(takes.latest){if(takes.latest.isNotBlank()&&takes.latest!=handledLatest){path=takes.latest;handledLatest=takes.latest;choosing=false}}
    val source=sources.firstOrNull{it.path==path}
    PanelBackHandler(choosing&&source!=null){choosing=false}
    Tools{Action(if(source==null)"Choose a source" else "Change source",tag="Lab choose source"){choosing=!choosing}}
    if(choosing||source==null){
        OutlinedTextField(query,{query=it.take(128)},label={Text("Find recording or project sample")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().heightIn(max=200.dp).verticalScroll(rememberScrollState())){
            sources.filter{it.name.contains(query,true)}.forEach{item->TextButton({path=item.path;choosing=false;service.stopSelectionPreview()},modifier=Modifier.fillMaxWidth().testTag(item.tag)){
                Text("${if(item.take)"Take" else "Sample"} · ${item.name}\n${time(item.frames,item.rate)}",fontSize=12.sp)}}
        }
        if(sources.isEmpty())Text("Record a take or open an audio file. Project samples also appear here.",fontSize=12.sp,color=Muted)
    }
    if(source!=null)key(source.path){SelectionEditor(source,s,service,slot,!takes.active,save)}
    Text(s.message,fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp).testTag("Lab message"))
}

@Composable private fun SelectionEditor(source: LabSource,s: StudioState,service: AudioService,slot: Int,enabled: Boolean,save: (String,String,Long,Long)->Unit){
    var first by rememberSaveable{mutableLongStateOf(0)};var last by rememberSaveable{mutableLongStateOf(source.frames)}
    var begin by rememberSaveable{mutableLongStateOf(0)};var end by rememberSaveable{mutableLongStateOf(source.frames)}
    var pan by rememberSaveable{mutableStateOf(false)}
    var waveform by remember{mutableStateOf(FloatArray(0))};var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")};var result by remember{mutableStateOf<BassResult?>(null)}
    var analyzing by remember{mutableStateOf(false)};var analysisJob by remember{mutableStateOf<Job?>(null)}
    var fade by rememberSaveable{mutableStateOf(false)};var root by rememberSaveable{mutableStateOf("60")}
    var discard by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope();val keyboard=LocalSoftwareKeyboardController.current
    fun window(a: Long,b: Long){val span=(b-a).coerceIn(min(16L,source.frames),source.frames);begin=a.coerceIn(0,source.frames-span);end=begin+span}
    fun zoom(factor: Double){val span=((end-begin)*factor).roundToLong().coerceIn(min(16L,source.frames),source.frames);val centre=(begin+end)/2;window(centre-span/2,centre+(span+1)/2)}
    LaunchedEffect(source.path,begin,end){loading=true;error="";waveform=FloatArray(0)
        try{waveform=withContext(Dispatchers.IO){val job=currentCoroutineContext();WaveFile(File(source.path)).use{it.envelope(begin,end){job.ensureActive()}}}}
        catch(e: CancellationException){throw e}catch(e: Exception){error=e.message?:"Could not read waveform."}finally{loading=false}
    }
    LaunchedEffect(first,last){analysisJob?.cancel();result=null;analyzing=false}
    DisposableEffect(Unit){onDispose{service.stopSelectionPreview();analysisJob?.cancel()}}
    Text(source.name,color=Green,fontSize=14.sp,modifier=Modifier.padding(top=12.dp))
    Text("${source.rate} Hz · ${time(source.frames,source.rate)} · source preserved",fontSize=12.sp,color=Muted)
    Tools{
        Action("Zoom +",tag="Lab zoom in"){zoom(.5)};Action("Zoom −"){zoom(2.0)}
        Action("Fit selection",tag="Lab fit selection"){window(first,last)};Action("Fit all"){window(0,source.frames)}
        Action("◀ Pan"){val shift=(end-begin)/2;window(begin-shift,end-shift)};Action("Pan ▶"){val shift=(end-begin)/2;window(begin+shift,end+shift)}
    }
    Tools{FilterChip(!pan,{pan=false},label={Text("Drag to select")});FilterChip(pan,{pan=true},label={Text("Pan / pinch zoom")})}
    val selectionColor=Green.copy(alpha=.17f)
    var anchor by remember{mutableLongStateOf(0)}
    val currentBegin by rememberUpdatedState(begin);val currentEnd by rememberUpdatedState(end)
    val gestures=if(pan)Modifier.pointerInput(source.path,true){detectTransformGestures{centroid,delta,scale,_ ->
        val span=currentEnd-currentBegin;val next=(span/scale).roundToLong().coerceIn(min(16L,source.frames),source.frames)
        val centre=currentBegin+span*(centroid.x/size.width);val a=(centre-next*(centroid.x/size.width)-delta.x/size.width*span).roundToLong();window(a,a+next)
    }}else Modifier.pointerInput(source.path,false){
        fun frame(x: Float)=(currentBegin+(currentEnd-currentBegin)*x/size.width).roundToLong().coerceIn(0,source.frames-1)
        detectDragGestures(onDragStart={p->anchor=frame(p.x);first=anchor;last=anchor+1}){change,_->change.consume();val value=frame(change.position.x);first=min(anchor,value);last=max(anchor,value)+1}
    }
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF020704)).then(gestures).testTag("Lab waveform")){
        val span=(end-begin).toDouble();val startX=((first-begin)/span*size.width).toFloat().coerceIn(0f,size.width);val endX=((last-begin)/span*size.width).toFloat().coerceIn(0f,size.width)
        drawRect(selectionColor,Offset(startX,0f),Size(max(0f,endX-startX),size.height))
        drawLine(Grid,Offset(0f,size.height/2),Offset(size.width,size.height/2))
        val n=waveform.size/2
        repeat(n){i->val x=(i+.5f)/n*size.width;drawLine(Green,Offset(x,(.5f-.46f*waveform[2*i+1].coerceIn(-1f,1f))*size.height),Offset(x,(.5f-.46f*waveform[2*i].coerceIn(-1f,1f))*size.height),max(1f,size.width/n))}
        if(end-begin<n)for(i in 1 until n)for(channel in 0..1){
            val previous=Offset((i-.5f)/n*size.width,(.5f-.46f*waveform[2*(i-1)+channel].coerceIn(-1f,1f))*size.height)
            val next=Offset((i+.5f)/n*size.width,(.5f-.46f*waveform[2*i+channel].coerceIn(-1f,1f))*size.height)
            drawLine(Green,previous,next,1.5f)
        }
        if(first in begin..end)drawLine(Green,Offset(startX,0f),Offset(startX,size.height),2f)
        if(last in begin..end)drawLine(Green,Offset(endX,0f),Offset(endX,size.height),2f)
    }
    Text("View ${time(begin,source.rate)} – ${time(end,source.rate)}${if(loading)" · reading…" else ""}",color=Muted,fontSize=11.sp,modifier=Modifier.testTag("Lab view range"))
    Text("Selection ${time(first,source.rate)} – ${time(last,source.rate)} · ${last-first} frames",fontSize=12.sp,modifier=Modifier.testTag("Lab selection"))
    var startText by rememberSaveable(first){mutableStateOf(first.toString())};var endText by rememberSaveable(last){mutableStateOf(last.toString())}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(startText,{startText=it.take(12)},label={Text("Start frame")},singleLine=true,modifier=Modifier.weight(1f).testTag("Lab start frame"))
        OutlinedTextField(endText,{endText=it.take(12)},label={Text("End frame (exclusive)")},singleLine=true,modifier=Modifier.weight(1f).testTag("Lab end frame"))
    }
    val a=startText.toLongOrNull();val b=endText.toLongOrNull()
    Tools{
        Action("Apply frame range",a!=null&&b!=null&&a>=0&&b>a&&b<=source.frames,"Lab apply range"){first=a!!;last=b!!;keyboard?.hide()}
        Action("Select view"){first=begin;last=end};Action("Select all"){first=0;last=source.frames}
    }
    Tools{
        Action("▶ Audition selection",enabled&&!s.busy,"Lab audition"){keyboard?.hide();service.previewSelection(source.path,first,last)}
        Action("■ Stop audition",tag="Lab stop audition"){service.stopSelectionPreview()}
        Action(if(analyzing)"Analyzing…" else "Analyze bass",!analyzing&&!s.busy,"Lab analyze"){
            keyboard?.hide();analysisJob?.cancel();analyzing=true;error=""
            analysisJob=scope.launch{try{val found=withContext(Dispatchers.Default){val job=currentCoroutineContext();WaveFile(File(source.path)).use{BassAnalysis.analyze(it,first,last){job.ensureActive()}}};result=found;found.midi?.takeIf{it in 0..127}?.let{root=it.toString()}}
                catch(e: CancellationException){throw e}catch(e: Exception){error=e.message?:"Could not analyze selection."}finally{analyzing=false}}
        }
    }
    result?.let{r->
        Text(if(r.hz!=null)"${r.note} · ${number(r.hz)} Hz · ${r.cents} cents" else "No stable pitch found",color=Green,modifier=Modifier.testTag("Lab pitch"))
        Text("Periodicity ${(r.confidence*100).roundToInt()}% · peak ${db(r.peak)} dBFS · RMS ${db(r.rms)} dBFS\nAnalyzed ${number(r.seconds)} s from selection start (maximum 2 s). Noisy or changing tones may have ambiguous fundamentals.",fontSize=11.sp,color=Muted)
        Canvas(Modifier.fillMaxWidth().height(112.dp).background(Color(0xFF020704)).testTag("Lab spectrum")){
            fun point(k: Int)=Offset(k/(r.spectrum.size-1f)*size.width,(1-(r.spectrum[k]+100)/106)*size.height)
            for(i in 1 until r.spectrum.size)drawLine(Green,point(i-1),point(i),2f)
        }
        Text("20 Hz                       200 Hz                       2 kHz · log frequency",fontSize=10.sp,color=Muted)
    }
    if(error.isNotBlank())Text(error,color=Color(0xFFFFA58D),fontSize=12.sp)
    OutlinedTextField(root,{root=it.take(3)},label={Text("Sampler root MIDI note (0–127)")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Lab root note"))
    FilterChip(fade,{fade=!fade},label={Text("2 ms edge fades")},modifier=Modifier.testTag("Lab edge fades"))
    Text("Saved as 32-bit float at the source rate. No normalization. With edge fades off, selection samples are unchanged. Only the selection uses the project's 512 MiB audio budget.",fontSize=11.sp,color=Muted)
    val canSave=enabled&&!s.busy&&root.toIntOrNull() in 0..127
    Tools{
        Action("Use as sampler",canSave,"Lab use sampler"){keyboard?.hide();service.stopSelectionPreview();service.sampleSelection(source.path,first,last,slot,true,root.toInt(),fade)}
        Action("Add audio clip",enabled&&!s.busy,"Lab add clip"){keyboard?.hide();service.stopSelectionPreview();service.sampleSelection(source.path,first,last,slot,false,60,fade)}
        if(source.take)Action("Discard original take",enabled&&!s.busy,"Lab discard"){discard=true}
    }
    Tools{
        Action("Save original WAV",enabled&&!s.busy,"Lab save original"){save(source.path,source.name,0,source.frames)}
        Action("Save selection WAV",enabled&&!s.busy,"Lab save selection"){save(source.path,source.name+" cut",first,last)}
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("Discard this original take?")},text={Text("Extracted samples already in a project remain available. This removes the original recording from this device.")},confirmButton={TextButton({service.stopSelectionPreview();service.recorder.discard(source.path);discard=false}){Text("Discard")}},dismissButton={TextButton({discard=false}){Text("Keep")}})
}
