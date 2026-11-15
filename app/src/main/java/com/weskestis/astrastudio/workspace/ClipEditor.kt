package com.weskestis.astrastudio.workspace

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.fx.ValueSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

private val noteNames=listOf("C","C♯","D","E♭","E","F","F♯","G","A♭","A","B♭","B")
private fun noteName(n: Int)="${noteNames[n%12]}${n/12-1}"

@Composable internal fun ClipEditor(c: Clip,s: StudioState,service: AudioService){
    var properties by remember{mutableStateOf(false)}
    Tools {Action("Name / length"){properties=true};Action("Duplicate clip"){service.projectEdit{p->p.duplicate(c.slot,p.emptySlot(c.track)?:error("This track has no empty slots."))}};Text(if(c.isAudio)"AUDIO · original speed" else "MIDI · ${c.notes.size}/256 notes",color=Muted,fontSize=11.sp)}
    if(c.isAudio)AudioClipEditor(c,s,service)else PianoRoll(c,service)
    AutomationEditor(c,service)
    if(!c.isAudio){Text("PLAY / TRACK ${c.track+1}",color=Green,fontSize=11.sp,modifier=Modifier.padding(top=16.dp));Keyboard(c.track,s.stats.running,service)}
    if(properties){var name by remember(c.slot){mutableStateOf(c.name)};var length by remember(c.slot){mutableStateOf(c.length.toString())}
        AlertDialog(onDismissRequest={properties=false},title={Text("Clip properties")},text={Column{OutlinedTextField(name,{name=it.take(64)},label={Text("Clip name")},singleLine=true);NumberField("Length in quarter-note beats",length,{length=it});Text("Arrangement regions keep their own lengths when you change their source clip.",fontSize=12.sp,color=Muted)}},confirmButton={TextButton({service.clipEdit(c.slot){it.resize(length.toDouble()).copy(name=name.trim())};properties=false},enabled=name.isNotBlank()&&length.toDoubleOrNull()?.let{it.isFinite()&&it in .0625..65536.0}==true){Text("Apply")}},dismissButton={TextButton({properties=false}){Text("Cancel")}})
    }
}

@Composable private fun PianoRoll(c: Clip,service: AudioService){
    var start by rememberSaveable(c.slot){mutableDoubleStateOf(0.0)};var span by rememberSaveable(c.slot){mutableDoubleStateOf(min(8.0,c.length))}
    var low by rememberSaveable(c.slot){mutableIntStateOf(((c.notes.minOfOrNull{it.pitch}?:48)/12*12).coerceIn(0,116))}
    var grid by rememberSaveable(c.slot){mutableDoubleStateOf(.25)};var tool by rememberSaveable{mutableIntStateOf(0)}
    var selected by rememberSaveable(c.slot){mutableIntStateOf(-1)};var preview by remember{mutableStateOf<MidiNote?>(null)}
    var original by remember{mutableStateOf<MidiNote?>(null)};var delta by remember{mutableStateOf(Offset.Zero)};var resizing by remember{mutableStateOf(false)}
    val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(162,186,167)}}
    val current by rememberUpdatedState(c)
    val n=c.note(selected)
    LaunchedEffect(c.length){span=min(span,c.length);start=min(start,max(0.0,c.length-span))}
    Tools{
        listOf("Draw","Select","Erase").forEachIndexed{i,label->FilterChip(tool==i,{tool=i},label={Text(label)},modifier=Modifier.testTag(label))}
        Action("Quantize"){service.clipEdit(c.slot){it.quantize(grid)}};Action("Legato"){service.clipEdit(c.slot){it.legato()}}
    }
    Tools{
        listOf("1/16" to .25,"1/8" to .5,"1/4" to 1.0,"Triplet" to (1.0/3)).forEach{(label,v)->FilterChip(abs(grid-v)<1e-7,{grid=v},label={Text(label)})}
        Action("Oct −",low>=12){low-=12};Text(noteName(low),fontSize=12.sp);Action("Oct +",low<=104){low+=12}
    }
    Tools{
        Action("←"){start=max(0.0,start-span)};Action("→"){start=min(max(0.0,c.length-span),start+span)}
        Action("−"){span=min(c.length,span*2);start=min(start,max(0.0,c.length-span))};Action("+"){span=max(.5,span/2).coerceAtMost(c.length)}
        Text("${number(start)}–${number(start+span)} beats",color=Muted,fontSize=11.sp)
    }
    Canvas(Modifier.fillMaxWidth().height(300.dp).testTag("Piano roll").semantics{contentDescription="Piano roll. Draw notes by tapping; drag notes to move, or their right edge to resize. Exact note controls follow below."}
        .pointerInput(c.slot,low,start,span,grid,tool){detectTapGestures{point->val left=40.dp.toPx();if(point.x>=left){val beat=(start+(point.x-left)/(size.width-left)*span).coerceIn(0.0,current.length-1.0/960);val pitch=(low+11-floor(point.y/size.height*12).toInt()).coerceIn(0,127)
            val hit=current.notes.lastOrNull{it.pitch==pitch&&beat>=it.beat&&beat<=it.beat+max(it.length,span*12.dp.toPx()/(size.width-left))}
            if(hit!=null){selected=hit.id;if(tool==2)service.clipEdit(c.slot){it.copy(notes=it.notes.filter{note->note.id!=hit.id})}}
            else if(tool==0){selected=current.nextId();service.clipEdit(c.slot){it.add(floor(beat/grid)*grid,pitch,grid)}}
        }}}
        .pointerInput(c.slot,low,start,span,grid,tool){detectDragGestures(onDragStart={point->val left=40.dp.toPx();val beat=start+(point.x-left)/(size.width-left)*span;val pitch=low+11-floor(point.y/size.height*12).toInt()
            original=if(tool==2||point.x<left)null else current.notes.lastOrNull{it.pitch==pitch&&beat>=it.beat&&beat<=it.beat+max(it.length,span*12.dp.toPx()/(size.width-left))}
            original?.let{selected=it.id;resizing=beat>it.beat+it.length*.65};preview=original;delta=Offset.Zero},onDrag={change,amount->original?.let{note->change.consume();delta+=amount
                val shift=round(delta.x/(size.width-40.dp.toPx())*span/grid)*grid
                preview=if(resizing)note.copy(length=(note.length+shift).coerceIn(1.0/960,current.length-note.beat))else{val beat=(note.beat+shift).coerceIn(0.0,max(0.0,current.length-note.length));note.copy(beat=beat,pitch=(note.pitch-round(delta.y/size.height*12).toInt()).coerceIn(0,127))}
            }},onDragEnd={preview?.let{note->service.clipEdit(c.slot){it.editNote(note.id,note.beat,note.pitch,note.length)}};preview=null;original=null},onDragCancel={preview=null;original=null})}){
        val left=40.dp.toPx();val width=size.width-left;val row=size.height/12
        val scale=if(c.minor)listOf(0,2,3,5,7,8,10)else listOf(0,2,4,5,7,9,11)
        paint.textSize=10.sp.toPx()
        repeat(12){i->val pitch=low+11-i;val inScale=(pitch-c.root+120)%12 in scale;val y=i*row
            drawRect(if(inScale)Color(0xFF102418)else Color(0xFF070D09),Offset(0f,y),Size(size.width,row));drawLine(Grid,Offset(left,y),Offset(size.width,y));drawContext.canvas.nativeCanvas.drawText(noteName(pitch),2.dp.toPx(),y+row*.67f,paint)}
        clipRect(left,0f,size.width,size.height){var beat=floor(start/grid)*grid;var count=0;while(beat<=start+span&&count++<1024){val x=left+((beat-start)/span*width).toFloat();drawLine(if(abs(beat-round(beat))<1e-6)Grid else Grid.copy(alpha=.4f),Offset(x,0f),Offset(x,size.height));beat+=grid}
            val notes=if(preview==null)c.notes else c.notes.filter{it.id!=preview!!.id}+preview!!
            for(note in notes){if(note.pitch !in low..low+11)continue;val x=left+((note.beat-start)/span*width).toFloat();val y=(low+11-note.pitch)*row+2.dp.toPx();val w=max(3.dp.toPx(),(note.length/span*width).toFloat())
                drawRoundRect(if(note.id==selected)Color(0xFFB6F56B)else Green.copy(alpha=.35f+.65f*note.velocity),Offset(x,y),Size(w,row-4.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                if(note.id==selected)drawLine(Color.Black,Offset(x+w-4.dp.toPx(),y+3.dp.toPx()),Offset(x+w-4.dp.toPx(),y+row-7.dp.toPx()),2.dp.toPx())
            }
        }
    }
    Text("VELOCITY · tap or drag a bar",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=6.dp))
    var velocityNote by remember{mutableIntStateOf(-1)};var velocity by remember{mutableFloatStateOf(.8f)}
    Canvas(Modifier.fillMaxWidth().height(84.dp).testTag("Velocity lane")
        .pointerInput(c.notes,start,span){detectTapGestures{point->val beat=start+point.x/size.width*span;val note=c.notes.filter{it.beat in start..start+span}.minByOrNull{abs(it.beat-beat)};note?.let{selected=it.id;service.clipEdit(c.slot){clip->clip.editNote(it.id,velocity=(1-point.y/size.height).coerceIn(.001f,1f))}}}}
        .pointerInput(c.notes,start,span){detectDragGestures(onDragStart={point->val beat=start+point.x/size.width*span;velocityNote=c.notes.filter{it.beat in start..start+span}.minByOrNull{abs(it.beat-beat)}?.id?:-1},onDrag={change,_->change.consume();velocity=(1-change.position.y/size.height).coerceIn(.001f,1f)},onDragEnd={if(velocityNote>=0)service.clipEdit(c.slot){it.editNote(velocityNote,velocity=velocity)};velocityNote=-1},onDragCancel={velocityNote=-1})}){
        drawRect(Color(0xFF09150D));for(note in c.notes){val x=((note.beat-start)/span*size.width).toFloat();val v=if(note.id==velocityNote)velocity else note.velocity;drawLine(if(note.id==selected)Color(0xFFB6F56B)else Green,Offset(x,size.height),Offset(x,size.height*(1-v)),max(3.dp.toPx(),(grid/span*size.width*.5).toFloat()))}
    }
    Tools{Action("Root: ${noteNames[c.root]}"){service.clipEdit(c.slot){it.copy(root=(it.root+1)%12)}};FilterChip(c.minor,{service.clipEdit(c.slot){it.copy(minor=!it.minor)}},label={Text(if(c.minor)"Minor scale" else "Major scale")});Text("Highlight only",color=Muted,fontSize=10.sp)}
    if(n!=null){
        Text("SELECTED · ${noteName(n.pitch)} · ${number(n.beat)} beat",color=Green,fontSize=12.sp)
        Tools{Action("Pitch −",n.pitch>0){service.clipEdit(c.slot){it.editNote(n.id,pitch=n.pitch-1)}};Action("Pitch +",n.pitch<127){service.clipEdit(c.slot){it.editNote(n.id,pitch=n.pitch+1)}};Action("Delete note"){service.clipEdit(c.slot){it.copy(notes=it.notes.filter{v->v.id!=n.id})}}}
        ValueSlider("Velocity",n.velocity,.001f..1f,change={v->service.clipEdit(c.slot,"velocity-${c.slot}-${n.id}"){it.editNote(n.id,velocity=v)}})
        var at by remember(n.id,n.beat){mutableStateOf(n.beat.toString())};var length by remember(n.id,n.length){mutableStateOf(n.length.toString())}
        NumberField("Note start beat",at,{at=it});NumberField("Note length beats",length,{length=it})
        Action("Apply note",at.toDoubleOrNull()?.isFinite()==true&&length.toDoubleOrNull()?.isFinite()==true){service.clipEdit(c.slot){it.editNote(n.id,beat=at.toDouble(),duration=length.toDouble())}}
    }
}

@Composable private fun AudioClipEditor(c: Clip,s: StudioState,service: AudioService){
    val a=s.project.asset(c.asset)?:return
    var first by remember(c.first){mutableStateOf(c.first.toString())};var last by remember(c.last){mutableStateOf(c.last.toString())}
    Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("Audio waveform")){drawRect(Color(0xFF09150D));clipPreview(c,s.project,Offset.Zero,size,Green);drawLine(Grid,Offset(0f,size.height/2),Offset(size.width,size.height/2))}
    Text("${a.name} · ${a.rate} Hz · ${number((c.last-c.first).toDouble()/a.rate)} seconds\nPlayback keeps the recording's original speed and pitch.",color=Muted,fontSize=12.sp)
    NumberField("First source sample (inclusive)",first,{first=it});Tools{Action("Start −1"){first=max(0,(first.toLongOrNull()?:0)-1).toString()};Action("Start +1"){first=((first.toLongOrNull()?:0)+1).toString()}}
    NumberField("Last source sample (exclusive)",last,{last=it});Tools{Action("End −1"){last=max(1,(last.toLongOrNull()?:1)-1).toString()};Action("End +1"){last=min(a.frames,(last.toLongOrNull()?:0)+1).toString()}}
    Action("Apply sample trim",first.toLongOrNull()?.let{x->last.toLongOrNull()?.let{y->x>=0&&y>x&&y<=a.frames}==true}==true){service.clipEdit(c.slot){WarpSettings.trim(it,first.toLong(),last.toLong(),a,s.bpm)}}
    Text("Trimming changes this source clip everywhere it is used. If a region starts beyond the new end, remove that region first.",color=Muted,fontSize=11.sp)
}

@Composable private fun AutomationEditor(c: Clip,service: AudioService){
    var lane by rememberSaveable(c.slot){mutableIntStateOf(0)};var erase by remember{mutableStateOf(false)}
    var draft by remember{mutableStateOf<Clip?>(null)};val current by rememberUpdatedState(c)
    fun value(y: Float,h: Int): Float {val normal=(1-y/h).coerceIn(0f,1f);return if(lane==2)(20*exp(normal*ln(900.0))).toFloat() else Clip.autoLow[lane]+normal*(Clip.autoHigh[lane]-Clip.autoLow[lane])}
    fun edit(base: Clip,point: Offset,w: Int,h: Int): Clip {val at=(point.x/w*c.length).coerceIn(0.0,c.length)
        return if(erase){val nearest=base.automation[lane].minByOrNull{abs(it.beat-at)};base.copy(automation=base.automation.toMutableList().also{it[lane]=it[lane].filter{p->p!=nearest}})}else runCatching{base.point(lane,at,value(point.y,h),max(c.length/63,1.0/960))}.getOrDefault(base)}
    Text("AUTOMATION",color=Green,fontSize=12.sp,modifier=Modifier.padding(top=20.dp))
    Tools{Clip.autoNames.forEachIndexed{i,name->FilterChip(lane==i,{lane=i;draft=null},label={Text(name)})}}
    Tools{FilterChip(erase,{erase=!erase},label={Text("Erase points")});Action("Clear lane"){service.clipEdit(c.slot){it.copy(automation=it.automation.toMutableList().also{a->a[lane]=emptyList()})}};Text("${c.automation[lane].size}/64 points",fontSize=11.sp,color=Muted)}
    Canvas(Modifier.fillMaxWidth().height(150.dp).testTag("Automation lane").semantics{contentDescription="${Clip.autoNames[lane]} automation. Draw a curve, up to 64 points."}
        .pointerInput(c.slot,lane,erase){detectTapGestures{point->val edited=edit(current,point,size.width,size.height);service.clipEdit(c.slot){it.copy(automation=edited.automation)}}}
        .pointerInput(c.slot,lane,erase){detectDragGestures(onDragStart={point->draft=edit(current,point,size.width,size.height)},onDrag={change,_->change.consume();draft=edit(draft?:current,change.position,size.width,size.height)},onDragEnd={draft?.let{edited->service.clipEdit(c.slot){it.copy(automation=edited.automation)}};draft=null},onDragCancel={draft=null})}){
        drawRect(Color(0xFF09150D));repeat(5){val y=it*size.height/4;drawLine(Grid,Offset(0f,y),Offset(size.width,y))}
        val points=(draft?:c).automation[lane];val path=Path()
        for((index,p)in points.withIndex()){val normal=if(lane==2)(ln(p.value/20.0)/ln(900.0)).toFloat()else(p.value-Clip.autoLow[lane])/(Clip.autoHigh[lane]-Clip.autoLow[lane]);val pos=Offset((p.beat/c.length*size.width).toFloat(),size.height*(1-normal));if(index==0){path.moveTo(0f,pos.y);path.lineTo(pos.x,pos.y)}else path.lineTo(pos.x,pos.y);if(index==points.lastIndex)path.lineTo(size.width,pos.y);drawCircle(Green,4.dp.toPx(),pos)}
        drawPath(path,Green,style=Stroke(2.dp.toPx()))
    }
    Text(if(lane==3)"Map macro 1 to an effect parameter in the effects rack first. Empty lanes use the track's normal setting." else "Draw across the lane to automate the clip. Empty lanes use the track's normal setting.",color=Muted,fontSize=11.sp)
}

@Composable internal fun Keyboard(track: Int,enabled: Boolean,service: AudioService){
    var octave by rememberSaveable{mutableIntStateOf(3)};var pressed by remember{mutableStateOf(setOf<Int>())}
    val scope=rememberCoroutineScope();val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(20,50,30)}}
    val white=listOf(0,2,4,5,7,9,11);val black=listOf(1 to 1,3 to 2,6 to 4,8 to 5,10 to 6)
    Tools{Action("Octave −",octave>0){octave--};Text("C$octave · ${if(enabled)"hold keys to play" else "start audio to play"}",color=Muted,fontSize=12.sp);Action("Octave +",octave<8){octave++}}
    DisposableEffect(track,octave){onDispose{service.releaseTouchNotes()}}
    Canvas(Modifier.fillMaxWidth().height(144.dp).testTag("Keyboard").semantics{
        contentDescription="One octave piano keyboard, C$octave"
        customActions=(0..11).map{key->CustomAccessibilityAction("Play ${noteName((octave+1)*12+key)}"){
            if(enabled)scope.launch{val id=900000+key;service.noteOn(track,(octave+1)*12+key,id);try{delay(220)}finally{service.noteOff(track,id)}};true}}
    }.pointerInput(track,octave,enabled){
        fun keyAt(p: Offset): Int? {if(p.x<0||p.x>=size.width||p.y<0||p.y>=size.height)return null;val w=size.width/7f
            if(p.y<size.height*.62f)black.firstOrNull{(_,pos)->p.x in (pos*w-w*.3f)..(pos*w+w*.3f)}?.let{return it.first}
            return white[(p.x/w).toInt().coerceIn(0,6)]}
        awaitEachGesture {
            val notes=mutableMapOf<PointerId,Pair<Int,Int>>()
            try{do{val event=awaitPointerEvent();event.changes.forEach{change->
                val key=if(enabled&&change.pressed)keyAt(change.position)else null;val old=notes[change.id]
                if(old?.first!=key){old?.let{service.noteOff(track,it.second);notes.remove(change.id)};if(key!=null){val id=500000+(change.id.value%300000).toInt();service.noteOn(track,(octave+1)*12+key,id);notes[change.id]=key to id}}
                if(change.pressed)change.consume()
            };pressed=notes.values.map{it.first}.toSet()}while(event.changes.any{it.pressed})}finally{notes.values.forEach{service.noteOff(track,it.second)};pressed=emptySet()}
        }
    }){
        val w=size.width/7;paint.textSize=12.sp.toPx()
        white.forEachIndexed{i,key->drawRect(if(key in pressed)Green else Color(0xFFD2E3D5),Offset(i*w+1,0f),Size(w-2,size.height));drawContext.canvas.nativeCanvas.drawText(noteNames[key]+if(key==0)octave else "",i*w+w*.23f,size.height-12.dp.toPx(),paint)}
        black.forEach{(key,pos)->drawRect(if(key in pressed)Color(0xFF319C4C)else Color(0xFF07110A),Offset(pos*w-w*.3f,0f),Size(w*.6f,size.height*.62f))}
    }
}
