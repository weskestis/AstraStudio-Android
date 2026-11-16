package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.fx.*
import com.weskestis.astrastudio.synth.SynthEditor
import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.astra.AstraPanel
import java.util.Locale

internal val Green=Color(0xFF55F27A)
internal val DimGreen=Color(0xFF15321E)
internal val Grid=Color(0xFF26472E)
internal val Muted=Color(0xFFA2BAA7)
internal fun number(value: Double)=String.format(Locale.US,"%.2f",value)
internal fun trackName(s: StudioState,t: Int)=s.tracks[t].name.ifBlank{"Track ${t+1}"}

@Composable internal fun Tools(content: @Composable RowScope.()->Unit){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp),content=content)}
@Composable internal fun Action(label: String,enabled: Boolean=true,tag: String=label,click: ()->Unit){OutlinedButton(click,enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag(tag),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)){Text(label,maxLines=1,fontSize=12.sp)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioWorkspace(state: StudioState,service: AudioService,start: ()->Unit,recordAudio: (Boolean)->Unit,saveLab: (String,String,Long,Long)->Unit,importLab: ()->Unit,importSamples: ()->Unit,importPack: ()->Unit,
    importAudio: (Int)->Unit,openProject: ()->Unit,saveProject: (String)->Unit,exportWave: (String,Int,Float,Boolean)->Unit) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    var trackPicker by rememberSaveable { mutableStateOf(false) }
    var fxUnit by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf("") }
    var parentSheet by rememberSaveable { mutableStateOf("") }
    var browserPad by rememberSaveable { mutableIntStateOf(-2) }
    val panelNavigation=remember(sheet){PanelNavigation()}
    val panelStates=rememberSaveableStateHolder()
    fun closePanel(){service.stopSelectionPreview();service.releaseTouchNotes();sheet="";parentSheet=""}
    fun backPanel(){service.stopSelectionPreview();service.releaseTouchNotes();if(!panelNavigation.back()){if(parentSheet.isNotEmpty()){sheet=parentSheet;parentSheet=""}else closePanel()}}
    fun browse(pad: Int=-2){parentSheet=sheet;browserPad=pad;sheet="samples"}
    BackHandler(enabled=sheet.isNotEmpty()||view!=0){if(sheet.isNotEmpty())backPanel()else view=0}
    var fileMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    var tempoDialog by remember { mutableStateOf(false) }
    var workVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy,state.work){workVisible=state.busy}
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var cursor by rememberSaveable { mutableDoubleStateOf(0.0) }
    var selectedRegion by rememberSaveable { mutableIntStateOf(-1) }
    var pending by remember { mutableStateOf<Pair<String,Int>?>(null) }
    val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<520
    val track=slot%ProjectLimits.TRACKS
    val editorCandidate=state.editorSnapshot();val editorState=remember(editorCandidate){editorCandidate}
    val sequencePhase=rememberUpdatedState(state.sequence.phase)
    val sessionCandidate=state.sessionSnapshot();val sessionState=remember(sessionCandidate){sessionCandidate}
    val audioRunning=state.stats.running
    fun audioAction(kind: String,id: Int=0){if(audioRunning){when(kind){"play"->service.play(view,cursor);"launch"->service.launch(id);"scene"->service.launchScene(id);"record"->service.record(id)}}else{pending=kind to id;start()}}
    LaunchedEffect(state.stats.running){if(state.stats.running)pending?.let{pending=null;when(it.first){"play"->service.play(view,cursor);"launch"->service.launch(it.second);"scene"->service.launchScene(it.second);"record"->service.record(it.second)}}}
    LaunchedEffect(track){service.meterTrack(track)}
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("workspace")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).height(48.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){Text("ASTRA / STUDIO",color=Green,fontSize=16.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp);Text(state.project.name,maxLines=1,overflow=TextOverflow.Ellipsis,color=Muted,fontSize=11.sp)}
            TextButton({sheet="astra"},enabled=state.ready,modifier=Modifier.testTag("Astra")){Text("Astra")}
            Box {TextButton({fileMenu=true},enabled=state.ready&&!state.busy,modifier=Modifier.testTag("File")){Text("File")}
                DropdownMenu(fileMenu,{fileMenu=false}){
                    fun act(action: ()->Unit){fileMenu=false;action()}
                    DropdownMenuItem({Text("New project")},{act{confirm="new"}},modifier=Modifier.testTag("New project"))
                    DropdownMenuItem({Text("Load starter song")},{act{confirm="starter"}},modifier=Modifier.testTag("Load starter song"))
                    DropdownMenuItem({Text("Project name / loop")},{act{sheet="project"}})
                    DropdownMenuItem({Text("Open .astra project")},{act{openProject()}})
                    DropdownMenuItem({Text("Save portable project")},{act{saveProject(state.project.name)}})
                    DropdownMenuItem({Text("Import audio to track ${track+1}")},{act{importAudio(slot)}})
                    DropdownMenuItem({Text("Export arrangement WAV")},{act{exportDialog=true}},modifier=Modifier.testTag("Export arrangement"))
                    DropdownMenuItem({Text("Share finished track")},{act{sheet="share"}},modifier=Modifier.testTag("Share finished track"))
                    DropdownMenuItem({Text("Sample browser")},{act{browserPad=-2;sheet="samples"}})
                    DropdownMenuItem({Text("Resample to audio")},{act{sheet="resample"}})
                    DropdownMenuItem({Text("Warp audio")},{act{sheet="warp"}})
                    DropdownMenuItem({Text("Stretch audio")},{act{sheet="stretch"}})
                    DropdownMenuItem({Text("Arrangement tempo map")},{act{sheet="tempo"}})
                    DropdownMenuItem({Text("Record & Sample Lab")},{act{sheet="record"}})
                    DropdownMenuItem({Text("Performance")},{act{sheet="performance"}},modifier=Modifier.testTag("Open performance"))
                    DropdownMenuItem({Text("Recovery")},{act{service.refreshRecovery();sheet="recovery"}},modifier=Modifier.testTag("Open recovery"))
                    DropdownMenuItem({Text("How to use")},{act{sheet="help"}})
                }
            }
        }
        if(!compact)Tools {
            Action(if(state.stats.running)"Audio on" else "Start audio",state.ready,"Audio"){if(state.stats.running)service.stopFromUi()else start()}
            Button({audioAction("play")},enabled=state.ready&&!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("Play")){Text("▶ Play")}
            Action("■ Stop",state.stats.running){pending=null;service.stopTransportFromUi()}
            FilterChip(state.recording>=0,{audioAction("record",slot)},enabled=state.ready&&!state.busy,label={Text(if(state.recording>=0)"● Recording" else "● Record MIDI")},modifier=Modifier.heightIn(min=48.dp).testTag("Record MIDI"))
            Action("${number(if(state.stats.playing&&state.project.mode==1&&state.project.tempoEnabled)state.stats.bpm.toDouble() else state.bpm.toDouble())} BPM",state.ready){tempoDialog=true}
            Action("Tap",state.ready){service.tapTempo()}
            FilterChip(state.metronome,{service.metronome(!state.metronome)},label={Text("Click")})
            FilterChip(state.overdub,{service.overdub(!state.overdub)},label={Text("Overdub")})
            Action("Panic"){service.panic()}
        }
        if(!compact)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically){
            Text(state.message,Modifier.weight(1f),fontSize=10.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(String.format(Locale.US,"  %.0f%% DSP",state.stats.cpuLoad*100),color=Green,fontSize=10.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(view==0,{view=0},label={Text("Session")},modifier=Modifier.testTag("Session"))
            FilterChip(view==1,{view=1},label={Text("Arrangement")},modifier=Modifier.testTag("Arrangement"))
            Action("Track ${track+1} / ${ProjectLimits.TRACKS}",state.ready,"Choose track"){trackPicker=true}
            if(compact){
                Action(if(state.stats.running)"Audio on" else "Start audio",state.ready,"Audio"){if(state.stats.running)service.stopFromUi()else start()}
                Action("▶ Play",state.ready,"Play"){audioAction("play")};Action("■ Stop",state.stats.running){pending=null;service.stopTransportFromUi()}
                Action(if(state.recording>=0)"● Recording" else "● MIDI",state.ready,"Record MIDI"){audioAction("record",slot)}
                Action("${number(if(state.stats.playing&&state.project.mode==1&&state.project.tempoEnabled)state.stats.bpm.toDouble() else state.bpm.toDouble())} BPM",state.ready){tempoDialog=true}
                Action("Tap"){service.tapTempo()}
                FilterChip(state.overdub,{service.overdub(!state.overdub)},label={Text("Overdub")})
                FilterChip(state.metronome,{service.metronome(!state.metronome)},label={Text("Click")})
            }
            Action("Undo",state.canUndo){service.undo()};Action("Redo",state.canRedo){service.redo()}
            RecordAction(service){sheet="record"}
            Action("Keys"){sheet="keys"}
            Action("Samples"){browserPad=-2;sheet="samples"}
            Action("Resample"){sheet="resample"}
            Action("Warp",tag="Warp"){sheet="warp"}
            Action("Stretch"){sheet="stretch"}
            Action("Tempo map"){sheet="tempo"}
            Action("${state.project.numerator}/${state.project.denominator}"){sheet="project"}
            Action("Launch: ${listOf("1 bar","¼","⅛")[state.project.quantization]}"){service.projectEdit{it.copy(quantization=(it.quantization+1)%3)}}
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(!state.ready)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            else if(view==0)SessionView(sessionState,slot,select={slot=it},edit={slot=it;sheet="clip"},create={service.newClip(it);slot=it;sheet="clip"},launch={audioAction("launch",it)},scene={audioAction("scene",it)},stop=service::stopTrack,mixer={slot=slot/ProjectLimits.TRACKS*ProjectLimits.TRACKS+it;sheet="mixer"},phase={sequencePhase.value[it]})
            else ArrangementView(state,slot,selectedRegion,cursor,select={selectedRegion=it.id;slot=it.slot},cursorChange={cursor=it},service=service,edit={sheet="clip"},trackSelect={slot=slot/ProjectLimits.TRACKS*ProjectLimits.TRACKS+it})
        }
        if(!compact)Tools {
            Text("T${track+1} · ${state.project.clip(slot)?.name?:"Empty slot"}",fontSize=11.sp,color=Green,maxLines=1)
            Action(if(state.project.clip(slot)==null)"+ MIDI" else "Edit clip",state.ready,"Edit clip"){if(state.project.clip(slot)==null)service.newClip(slot);sheet="clip"}
            Action("Duplicate",state.project.clip(slot)!=null){service.projectEdit{p->p.duplicate(slot,p.emptySlot(track)?:error("This track has no empty slots."))}}
            Action("Import audio",state.ready){importAudio(slot)}
            Action("Delete clip",state.project.clip(slot)!=null){confirm="delete"}
        }
        DeviceRack(editorState,track,service,compact){if(it=="clip"&&state.project.clip(slot)==null)service.newClip(slot);if(it.startsWith("effects:")){fxUnit=it.substringAfter(":").toInt();sheet="effects"}else sheet=it}
    }
    if(sheet.isNotEmpty())Dialog(onDismissRequest={backPanel()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,dismissOnClickOutside=false)){
        CompositionLocalProvider(LocalPanelNavigation provides panelNavigation){panelStates.SaveableStateProvider(sheet){
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),contentAlignment=Alignment.BottomCenter){
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f),color=Color(0xFF080F0A),shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)){
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(when(sheet){"clip"->"CLIP / ${state.project.clip(slot)?.name?:"New MIDI"}";"keys"->"KEYBOARD / ${trackName(state,track)}";"performance","recovery"->sheet.uppercase();else->"${sheet.uppercase()} / ${trackName(state,track)}"},Modifier.weight(1f),color=Green,fontWeight=FontWeight.Bold,fontSize=13.sp);TextButton({backPanel()},Modifier.testTag("Panel back")){Text("‹ Back")};TextButton({closePanel()},Modifier.testTag("Close panel")){Text("Close ×")}}
            when(sheet){
                "freeze"->FreezePanel(editorState,track,service)
                "performance"->PerformancePanel(state,service)
                "recovery"->RecoveryPanel(editorState,service)
                "share"->SharePanel(editorState,service)
                "record"->RecordingPanel(editorState,service,slot,recordAudio,importLab,saveLab)
                "astra"->AstraPanel(editorState,service,slot)
                "clip"->state.project.clip(slot)?.let{ClipEditor(it,editorState,service)}?:Text("Creating clip…")
                "synth"->{Tools{Action("Use synth",state.project.instruments[track].mode!=0){service.instrument(track,0)};Action("Sampler / drums"){sheet="instrument"}};SynthEditor(track,state.patches[track],service,state.stats.running)}
                "instrument"->SampleInstrumentPanel(editorState,track,slot,service,start){browse(it)}
                "samples"->SampleBrowser(editorState,track,slot,if(browserPad==-2)null else browserPad,service,start,importSamples,importPack){service.stopPreview();if(parentSheet.isNotEmpty()){sheet=parentSheet;parentSheet=""}else sheet="instrument"}
                "resample"->ResamplePanel(editorState,slot,service){browse()}
                "tempo"->TempoPanel(editorState,service)
                "warp"->WarpPanel(editorState,slot,service){sheet="resample"}
                "stretch"->StretchPanel(editorState,slot,service,{browse()},{sheet="resample"})
                "effects"->FxEditor(track,state,service,fxUnit)
                "mixer"->Mixer(editorState,track,service)
                "master"->MasterPanel(state,service)
                "project"->ProjectPanel(editorState,service)
                "keys"->{Tools{Action(if(state.stats.running)"Audio on" else "Start audio"){if(!state.stats.running)start()};Action(if(state.recording>=0)"Finish recording" else "Record to selected clip"){audioAction("record",slot)}};Keyboard(track,state.stats.running,service)}
                "help"->HelpPanel()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    }}}}
    if(trackPicker)AlertDialog(onDismissRequest={trackPicker=false},title={Text("Choose track · ${ProjectLimits.TRACKS} tracks")},
        text={val list=rememberLazyListState(initialFirstVisibleItemIndex=track)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp).testTag("Track picker"),state=list){
                items(ProjectLimits.TRACKS,key={it}){t->
                    TextButton({slot=slot/ProjectLimits.TRACKS*ProjectLimits.TRACKS+t;trackPicker=false},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("Select track ${t+1}")){
                        Text("${t+1} · ${trackName(state,t)}",Modifier.weight(1f),color=if(t==track)Green else Color.White)
                        if(t==track)Text("Selected",color=Green,fontSize=11.sp)
                    }
                }
            }},confirmButton={TextButton({trackPicker=false}){Text("Close")}})
    if(confirm.isNotEmpty())AlertDialog(onDismissRequest={confirm=""},title={Text(when(confirm){"delete"->"Delete this clip?";"starter"->"Load the starter song?";else->"Start a new project?"})},text={Text(if(confirm=="delete")"Its arrangement regions will also be removed. Undo restores them." else "This replaces the current composition. Save a portable project from File first if you want to keep it.")},confirmButton={TextButton({when(confirm){"delete"->service.projectEdit{it.remove(slot)};"starter"->{service.starter();slot=0;selectedRegion=-1;cursor=0.0};else->{service.newProject();slot=0;selectedRegion=-1;cursor=0.0}};confirm=""},Modifier.testTag("Confirm project")){Text("Continue")}},dismissButton={TextButton({confirm=""}){Text("Cancel")}})
    if(tempoDialog)TempoDialog(state.bpm,{tempoDialog=false}){service.tempo(it);tempoDialog=false}
    if(exportDialog)ExportDialog(state.project,{exportDialog=false}){rate,tail,floating->exportDialog=false;exportWave(state.project.name,rate,tail,floating)}
    if(state.busy&&state.ready&&workVisible)AlertDialog(onDismissRequest={workVisible=false},title={Text(state.work)},text={Column{if(state.work in listOf("Rendering WAV","Resampling","Stretching audio","Rendering finished track","Bouncing instrument","Freezing track"))LinearProgressIndicator(progress={state.progress.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth());Text("Back hides this window while the work continues. Cancel stops the operation.",fontSize=12.sp,modifier=Modifier.padding(top=12.dp))}},confirmButton={TextButton(service::cancelWork){Text("Cancel")}})
}

@Composable private fun DeviceRack(s: StudioState,t: Int,service: AudioService,compact: Boolean,open: (String)->Unit){
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B160E))){
        if(!compact)Text("DEVICE CHAIN · ${trackName(s,t)}",color=Muted,fontSize=9.sp,letterSpacing=1.sp,modifier=Modifier.padding(start=12.dp,top=4.dp))
        Tools {
            if(compact)Action("Edit clip",tag="Edit clip"){open("clip")}
            Action("Mixer"){open("mixer")};Action(if(s.project.frozen.any{it.track==t})"Frozen ›" else "Freeze track",tag="Open freeze"){open("freeze")};Action("${if(s.patches[t].wire[0]>0)"FM" else "Wavetable"} ›"){open("synth")}
            Action(when(s.project.instruments[t].mode){1->"Sampler ›";2->"Drum rack ›";else->"Sampler / drums"},tag="Sample instrument"){open("instrument")}
            s.effects[t].order.forEach{unit->val on=s.effects[t][FxConfig.enabled[unit]]>.5f
                FilterChip(on,{open("effects:$unit")},label={Text(listOf("EQ 8","Multiband","Shaper","Sidechain","Echo","Reverb","Chorus","Phaser")[unit],fontSize=11.sp)})}
            Action("Share track"){open("share")}
            Action("Master"){service.meterTrack(ProjectLimits.TRACKS);open("master")}
        }
    }
}
@Composable private fun Mixer(s: StudioState,t: Int,service: AudioService){
    val v=s.tracks[t]
    var name by remember(t,v.name){mutableStateOf(v.name)}
    OutlinedTextField(name,{name=it.take(32)},label={Text("Track name")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Action("Rename"){service.track(t,v.copy(name=name.trim()))}
    Tools{FilterChip(v.armed,{service.track(t,v.copy(armed=!v.armed))},label={Text("Arm")});FilterChip(v.mute,{service.track(t,v.copy(mute=!v.mute))},label={Text("Mute")});FilterChip(v.solo,{service.track(t,v.copy(solo=!v.solo))},label={Text("Solo")})}
    ValueSlider("Volume",v.gain,0f..2f,change={service.track(t,v.copy(gain=it))})
    ValueSlider("Pan",v.pan,-1f..1f,change={service.track(t,v.copy(pan=it))})
    ValueSlider("Send A · Reverb",v.sendA,0f..1f,change={service.track(t,v.copy(sendA=it))})
    ValueSlider("Send B · Ping-pong echo",v.sendB,0f..1f,change={service.track(t,v.copy(sendB=it))})
    Text("Sends follow track volume, mute and solo. "+if(t<16)"MIDI channel ${t+1} plays this track." else "Play this track with Keys, clips or Astra. USB MIDI channels address tracks 1–16.",color=Muted,fontSize=12.sp)
}
@Composable private fun MasterPanel(s: StudioState,service: AudioService){
    ValueSlider("Master volume",s.master,0f..1f,change=service::master)
    ValueSlider("Limiter drive dB",s.limiterDrive,0f..24f,change=service::limiterDrive)
    ValueSlider("Limiter release ms",s.limiterRelease,20f..500f,true,service::limiterRelease)
    Text("−0.1 dBFS ceiling · ${number(s.fxStats.limiterReduction.toDouble())} dB reduction",color=Green)
    Tools{listOf("Fast" to 2,"Balanced" to 3,"Stable" to 4,"Large" to 8).forEach{(name,b)->FilterChip(s.bufferBursts==b,{service.buffer(b)},label={Text(name)})}}
    Text("${s.stats.sampleRate} Hz · ${if(s.stats.exclusive)"Exclusive" else "Shared / idle"} · ${s.stats.activeVoices}/128 voices\nBuffer ${number(s.stats.bufferMs.toDouble())} ms · xruns ${s.stats.xruns}\nQueue drops ${s.stats.droppedCommands} · late events ${s.stats.lateCommands}\nMIDI inputs ${s.midiDevices}",fontSize=13.sp)
    Text("Audio pages ${number(s.storage.audioBytes/1048576.0)} / 128 MiB · ${s.storage.streamed} streamed files\nSource audio ${number(s.storage.sourceBytes/1073741824.0)} / 8 GiB equivalent\nStream missed source frames ${s.storage.missedFrames} · read errors ${s.storage.readErrors}",fontSize=13.sp,modifier=Modifier.testTag("Storage stats"))
    Text("A cache miss produces silence while the reader catches up. Exports use independent readers and fail on a file-read error. Audio pages exclude instruments, DSP, UI and temporary export memory.",color=Muted,fontSize=12.sp)
    Text("The buffer duration is not total input/output latency. Wired audio is best for live playing.",color=Muted,fontSize=12.sp)
}
@Composable private fun ProjectPanel(s: StudioState,service: AudioService){
    var name by remember(s.project.name){mutableStateOf(s.project.name)}
    var begin by remember(s.project.loopStart){mutableStateOf(number(s.project.loopStart))};var end by remember(s.project.loopEnd){mutableStateOf(number(s.project.loopEnd))}
    OutlinedTextField(name,{name=it.take(64)},label={Text("Project name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Action("Rename project",name.isNotBlank()){service.projectEdit{it.copy(name=name.trim())}}
    Tools{(1..12).forEach{n->FilterChip(s.project.numerator==n,{service.projectEdit{it.copy(numerator=n)}},label={Text("$n")})}}
    Tools{listOf(2,4,8).forEach{n->FilterChip(s.project.denominator==n,{service.projectEdit{it.copy(denominator=n)}},label={Text("/$n")})}}
    Text("Loop uses quarter-note beats, starting at 0. It applies to Arrangement playback.",color=Muted,fontSize=12.sp)
    NumberField("Loop start",begin,{begin=it});NumberField("Loop end",end,{end=it})
    Action("Apply loop range",begin.toDoubleOrNull()?.isFinite()==true&&end.toDoubleOrNull()?.isFinite()==true){service.projectEdit{it.copy(loopStart=begin.toDouble(),loopEnd=end.toDouble())}}
    FilterChip(s.project.loop,{service.projectEdit{it.copy(loop=!it.loop)}},label={Text("Loop arrangement")})
}
@Composable internal fun NumberField(label: String,value: String,onValue: (String)->Unit){OutlinedTextField(value,onValue,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=3.dp),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))}
@Composable private fun TempoDialog(bpm: Float,close: ()->Unit,apply: (Float)->Unit) {
    var value by remember { mutableStateOf(number(bpm.toDouble())) }
    AlertDialog(onDismissRequest=close,title={Text("Tempo")},text={
        Column {
            NumberField("BPM · 30–300",value,{value=it})
            Row { listOf(128,138,145).forEach { n -> TextButton({value=n.toString()}) { Text("$n") } } }
        }
    },confirmButton={TextButton({apply(value.toFloat())},enabled=value.toFloatOrNull()?.let{it in 30f..300f}==true){Text("Apply")}},dismissButton={TextButton(close){Text("Cancel")}})
}
@Composable private fun ExportDialog(p: Project,close: ()->Unit,apply: (Int,Float,Boolean)->Unit) {
    var rate by rememberSaveable { mutableIntStateOf(48000) }
    var tail by rememberSaveable { mutableStateOf("4") }
    var floating by rememberSaveable { mutableStateOf(true) }
    AlertDialog(onDismissRequest=close,title={Text("Export arrangement")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Stereo WAV · ${number(p.endBeat)} beats. Tempo map, master effects and sends are included; loop playback is ignored.")
            ExportFormatControls(rate,floating,{rate=it},{floating=it},"Export")
            NumberField("Effect tail seconds · 0–30",tail,{tail=it})
            if(p.regions.isEmpty())Text("Place clips in Arrangement first.",color=Green)
        }
    },confirmButton={TextButton({apply(rate,tail.toFloat(),floating)},enabled=p.regions.isNotEmpty()&&tail.toFloatOrNull()?.let{it in 0f..30f}==true){Text("Export")}},dismissButton={TextButton(close){Text("Cancel")}})
}
@Composable private fun HelpPanel(){Text("1. Use Track / 50 to jump to any track in Session or Arrangement. Start audio. Use File → Load starter song to explore a working session, or tap an empty Session slot to write a MIDI clip.\n\n2. Tap ▶ on clips or scenes to launch at the chosen quantization. Tap a clip body to select it; Edit clip opens its piano roll, velocity and automation lanes.\n\n3. Open Keys to play. Record MIDI writes the keyboard or USB controller into the selected clip after its launch boundary. Overdub adds notes; turn it off to replace the take.\n\n4. Select a clip, switch to Arrangement, tap the ruler for a cursor, then Place clip. Drag regions horizontally to move them. Split and duplicate build the song. Open Region for exact beat lengths or sample positions.\n\n5. The bottom rack opens synths and effects for the selected track. Mixer has volume, pan, arm, mute, solo and two effect sends.\n\n6. File → Import audio accepts WAV and formats supported by Android. Audio clips keep their original pitch and speed. Sampler / drums in the device chain plays samples with pitch, reverse, loops and envelopes. You can trim and split at source-sample boundaries.\n\n7. Work autosaves locally. Save portable project carries all audio, notes, automation and sound settings to a .astra file. Export arrangement creates a 32-bit float or 24-bit WAV.\n\nLimits: 50 tracks, 8 scenes (400 clip slots), 256 notes per clip, 2048 arrangement regions, 64 audio assets, 2 GiB per imported file and 8 GiB of stereo-float-equivalent source frames. Larger sources stream through bounded audio pages. Record audio opens microphone capture and Sample Lab for zoom, pitch analysis and extraction. Open Samples to import multiple files or a ZIP pack, audition, favorite and assign sounds. Resample creates a processed clip or arrangement bounce in the browser. Back returns from details to the parent panel, closes a panel, and returns Arrangement to Session before leaving the app. Tap Astra beside File for commands and Gemini. Tempo map adds BPM steps and ramps in Arrangement. Stretch renders a new audio clip with independent pitch and duration; it keeps the original. Warp saves source tempo, downbeat and pitch settings, and follows Session BPM or Arrangement tempo maps during playback and export.",fontSize=14.sp,lineHeight=21.sp)}
