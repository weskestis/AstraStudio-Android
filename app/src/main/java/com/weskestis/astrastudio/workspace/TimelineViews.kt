package com.weskestis.astrastudio.workspace

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.*
import com.weskestis.astrastudio.project.*
import kotlin.math.*

@Composable
internal fun SessionView(s: StudioState,selected: Int,select: (Int)->Unit,edit: (Int)->Unit,create: (Int)->Unit,
    launch: (Int)->Unit,scene: (Int)->Unit,stop: (Int)->Unit,mixer: (Int)->Unit,phase: (Int)->Float){
    val tracks=rememberLazyListState()
    LaunchedEffect(selected%ProjectLimits.TRACKS){
        val target=selected%ProjectLimits.TRACKS
        if(tracks.layoutInfo.visibleItemsInfo.none{it.index==target})tracks.scrollToItem(target)
    }
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("Session grid")){
        Column(Modifier.width(60.dp)){
            Box(Modifier.height(96.dp),contentAlignment=Alignment.Center){Text("SCENE",fontSize=9.sp,color=Muted)}
            repeat(ProjectLimits.SCENES){row->TextButton({scene(row)},Modifier.height(68.dp).fillMaxWidth().testTag("Scene ${row+1}")){Text("▶ ${row+1}")}}
        }
        LazyRow(Modifier.weight(1f).testTag("Session tracks"),state=tracks){
            items(ProjectLimits.TRACKS,key={it}){track->Column(Modifier.width(168.dp)){
                Column(Modifier.height(96.dp).fillMaxWidth().background(if(selected%ProjectLimits.TRACKS==track)DimGreen else Color(0xFF0A130D)).clickable{mixer(track)}.padding(8.dp),verticalArrangement=Arrangement.SpaceBetween){
                    Text("${track+1} / ${trackName(s,track)}${if(s.project.frozen.any{it.track==track})" ❄" else ""}",color=Green,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(listOfNotNull(if(s.tracks[track].armed)"ARM" else null,if(s.tracks[track].solo)"SOLO" else null,if(s.tracks[track].mute)"MUTE" else null).joinToString(" · ").ifEmpty{"Mixer / sends ›"},color=Muted,fontSize=10.sp)
                    Text("${s.patches[track].name}",fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                repeat(ProjectLimits.SCENES){row->val slot=row*ProjectLimits.TRACKS+track;val c=s.project.clip(slot);val active=s.sequence.active[track]==slot;val queued=s.sequence.queued[track]==row
                    Row(Modifier.height(68.dp).padding(2.dp).background(if(selected==slot)Color(0xFF1A3D24)else Color(0xFF0B160E)).border(1.dp,if(queued)Color(0xFFB6F56B)else if(active)Green else Grid),verticalAlignment=Alignment.CenterVertically){
                        if(c==null)TextButton({create(slot)},Modifier.fillMaxSize().testTag("Empty $slot")){Text("+",color=Muted)}
                        else{
                            TextButton({select(slot);launch(slot)},Modifier.width(48.dp).fillMaxHeight().testTag("Launch $slot"),contentPadding=PaddingValues(0.dp)){Text(if(queued)"…" else if(active)"▸" else "▶",color=if(active||queued)Green else Muted)}
                            Column(Modifier.weight(1f).fillMaxHeight().pointerInput(slot){detectTapGestures(onTap={select(slot)},onDoubleTap={edit(slot)},onLongPress={edit(slot)})}.padding(end=6.dp,top=6.dp)){
                                Text(c.name,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(active)Green else Color(0xFFE7F5E9),fontSize=11.sp)
                                Canvas(Modifier.fillMaxWidth().height(26.dp)){clipPreview(c,s.project,Offset.Zero,size,if(active)Green else Color(0xFF79AA85))}
                                if(active)LinearProgressIndicator(progress={phase(track).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(2.dp),color=Green)
                            }
                        }
                    }
                }
                TextButton({stop(track)},Modifier.fillMaxWidth().height(48.dp)){Text("■ Stop track",fontSize=11.sp)}
            }}
        }
    }
}

internal fun DrawScope.clipPreview(c: Clip,p: Project,origin: Offset,area: Size,color: Color,offset: Double=0.0,length: Double=c.length,audioOffset: Long?=null,bpm: Float=120f,regionBeat: Double?=null){
    if(area.width<=0||area.height<=0)return
    clipRect(origin.x,origin.y,origin.x+area.width,origin.y+area.height){
        if(c.isAudio){val a=p.asset(c.asset)?:return@clipRect
            val first=c.first.toDouble()/a.frames;val last=c.last.toDouble()/a.frames
            val steps=area.width.toInt().coerceIn(1,512)
            for(x in 0 until steps){val position=if(c.warp)WarpSettings.sourceFrame(c,a,(offset+length*x/steps)%c.length,audioOffset?:0)/a.frames else if(audioOffset==null)first+(last-first)*x/steps else (c.first+audioOffset+(if(regionBeat!=null&&p.tempoEnabled){val now=regionBeat+length*x/steps;val phase=(offset+length*x/steps)%c.length;val begin=now-phase;p.timelineSeconds(max(0.0,begin),now,bpm)+if(begin<0)-begin*60/TempoMap.bpmAt(p.tempoPoints,0.0,bpm)else 0.0}else ((offset+length*x/steps)%c.length)*60/bpm)*a.rate)/a.frames
                if(position<first||position>=last)continue
                val bin=(position*512).toInt().coerceIn(0,511)
                drawLine(color,Offset(origin.x+x*area.width/steps,origin.y+area.height*(.5f-a.waveform[bin*2].coerceIn(-1f,1f)*.45f)),Offset(origin.x+x*area.width/steps,origin.y+area.height*(.5f-a.waveform[bin*2+1].coerceIn(-1f,1f)*.45f)),max(1f,area.width/steps))}
        }else{
            val low=(c.notes.minOfOrNull{it.pitch}?:36)-1;val high=(c.notes.maxOfOrNull{it.pitch}?:60)+1
            val repeats=ceil(length/c.length).toInt().coerceIn(1,128)+1
            repeat(repeats){r->for(n in c.notes){val at=n.beat+r*c.length-offset
                if(at+n.length>=0&&at<length){val x=origin.x+(at/length*area.width).toFloat();val y=origin.y+(high-n.pitch).toFloat()/(high-low)*area.height
                    drawRect(color,Offset(x,y),Size(max(2f,(n.length/length*area.width).toFloat()),max(1.5f,area.height/(high-low+1))))}}}
        }
    }
}

@Composable private fun animatedBeat(s: StudioState): Double {
    val current by rememberUpdatedState(s)
    var beat by remember{mutableDoubleStateOf(s.stats.beat)}
    LaunchedEffect(s.stats.running,s.stats.playing,if(!s.stats.playing)s.stats.beat else 0.0){
        if(!s.stats.playing){beat=s.stats.beat;return@LaunchedEffect}
        var lastFrame=Long.MIN_VALUE;var received=0L
        while(true){withFrameNanos{now->val state=current
            if(state.stats.frame!=lastFrame){lastFrame=state.stats.frame;received=now}
            var next=if(state.stats.playing)TempoMap.advance(if(state.project.mode==1&&state.project.tempoEnabled)state.project.tempoPoints else emptyList(),state.stats.beat,min(.10,(now-received)/1e9),state.bpm)else state.stats.beat
            val p=state.project;if(p.mode==1&&p.loop&&next>=p.loopEnd)next=p.loopStart+(next-p.loopEnd)%(p.loopEnd-p.loopStart)
            beat=next
        }}
    }
    return beat
}

@Composable internal fun ArrangementView(s: StudioState,slot: Int,selected: Int,cursor: Double,
    select: (Region)->Unit,cursorChange: (Double)->Unit,service: AudioService,edit: ()->Unit,trackSelect: (Int)->Unit){
    var start by rememberSaveable{mutableDoubleStateOf(0.0)};var span by rememberSaveable{mutableDoubleStateOf(32.0)}
    var follow by rememberSaveable{mutableStateOf(false)};var showRegion by remember{mutableStateOf(false)}
    var drag by remember{mutableStateOf<Region?>(null)};var dragDelta by remember{mutableFloatStateOf(0f)}
    val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<520
    val beat=animatedBeat(s);val p=s.project
    val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(162,186,167)}}
    LaunchedEffect(follow,beat){if(follow&&s.stats.playing&&(beat<start||beat>=start+span))start=floor(beat/span)*span}
    val region=p.regions.firstOrNull{it.id==selected}
    val tracks=rememberLazyListState()
    val trackRegions=remember(p.regions){p.regions.groupBy{it.slot%ProjectLimits.TRACKS}}
    LaunchedEffect(slot%ProjectLimits.TRACKS){
        val target=slot%ProjectLimits.TRACKS
        if(tracks.layoutInfo.visibleItemsInfo.none{it.index==target})tracks.scrollToItem(target)
    }
    Column(Modifier.fillMaxSize().testTag("Arrangement timeline")){
        Tools{
            Action("Place clip",p.clip(slot)!=null){service.projectEdit{it.place(slot,cursor)}}
            Action("Split",region!=null){service.projectEdit{it.splitRegion(selected,cursor,s.bpm)}}
            Action("Duplicate region",region!=null){service.projectEdit{it.duplicateRegion(selected)}}
            Action("Region",region!=null){showRegion=true};Action("Edit source",region!=null,click=edit)
            FilterChip(p.loop,{service.projectEdit{it.copy(loop=!it.loop)}},label={Text("Loop")})
            if(compact){Action("←"){start=max(0.0,start-span/2)};Action("→"){start=min(65536.0-span,start+span/2)};Action("−"){span=min(256.0,span*2)};Action("+"){span=max(4.0,span/2)};FilterChip(follow,{follow=!follow},label={Text("Follow")})}
        }
        if(!compact)Tools{
            Action("←"){start=max(0.0,start-span/2)};Action("→"){start=min(65536.0-span,start+span/2)}
            Action("−"){span=min(256.0,span*2)};Action("+"){span=max(4.0,span/2)}
            Text("Beat ${number(cursor)}",fontSize=10.sp,color=Green)
            FilterChip(follow,{follow=!follow},label={Text("Follow")})
        }
        Canvas(Modifier.fillMaxWidth().height(36.dp).testTag("Timeline ruler").pointerInput(start,span){detectTapGestures{point->val left=104.dp.toPx();if(point.x>=left){val at=(start+(point.x-left)/(size.width-left)*span).coerceIn(0.0,65536.0);cursorChange(round(at*4)/4)}}}){
            val left=104.dp.toPx();val width=size.width-left
            drawRect(Color(0xFF0C1910));paint.textSize=10.sp.toPx()
            val bar=p.barBeats;val stride=max(bar,ceil(span/16/bar)*bar)
            var at=floor(start/stride)*stride
            while(at<=start+span){val x=left+((at-start)/span*width).toFloat();if(x>=left){drawLine(Grid,Offset(x,0f),Offset(x,size.height));drawContext.canvas.nativeCanvas.drawText("${(at/bar).toInt()+1}",x+3,16.dp.toPx(),paint)};at+=stride}
            if(p.loop){val x=left+((p.loopStart-start)/span*width).toFloat();val end=left+((p.loopEnd-start)/span*width).toFloat();clipRect(left,0f,size.width,size.height){drawLine(Green,Offset(x,28.dp.toPx()),Offset(end,28.dp.toPx()),3.dp.toPx());drawLine(Green,Offset(x,22.dp.toPx()),Offset(x,32.dp.toPx()),2.dp.toPx());drawLine(Green,Offset(end,22.dp.toPx()),Offset(end,32.dp.toPx()),2.dp.toPx())}}
            val x=left+((cursor-start)/span*width).toFloat();if(x>=left&&x<=size.width)drawLine(Color.White,Offset(x,0f),Offset(x,size.height),2.dp.toPx())
        }
        LazyColumn(Modifier.weight(1f).testTag("Timeline canvas"),state=tracks){
            items(ProjectLimits.TRACKS,key={it}){t->
            val regions=trackRegions[t].orEmpty()
            Canvas(Modifier.fillMaxWidth().height(68.dp).testTag("Timeline track ${t+1}")
                .pointerInput(p.regions,start,span){detectTapGestures(onDoubleTap={point->val at=start+(point.x-104.dp.toPx())/(size.width-104.dp.toPx())*span;regions.firstOrNull{at>=it.beat&&at<it.beat+it.length}?.let{select(it);edit()}},onTap={point->trackSelect(t);if(point.x>=104.dp.toPx()){val at=(start+(point.x-104.dp.toPx())/(size.width-104.dp.toPx())*span).coerceIn(0.0,65536.0);cursorChange(round(at*4)/4);regions.firstOrNull{at>=it.beat&&at<it.beat+it.length}?.let(select)}})}
                .pointerInput(p.regions,start,span){detectHorizontalDragGestures(onDragStart={point->val at=start+(point.x-104.dp.toPx())/(size.width-104.dp.toPx())*span;drag=if(point.x<104.dp.toPx())null else regions.firstOrNull{at>=it.beat&&at<it.beat+it.length};drag?.let(select);dragDelta=0f},onHorizontalDrag={change,amount->if(drag!=null){change.consume();dragDelta+=amount}},onDragCancel={drag=null},onDragEnd={drag?.let{r->val at=(round((r.beat+dragDelta/(size.width-104.dp.toPx())*span)*4)/4).coerceAtLeast(0.0);service.projectEdit{it.editRegion(r.id,beat=at)}};drag=null})}){
                val left=104.dp.toPx();val width=size.width-left;val row=68.dp.toPx()
                paint.textSize=11.sp.toPx()
                run{val y=0f;drawRect(if(t==slot%ProjectLimits.TRACKS)Color(0xFF102218)else if(t%2==0)Color(0xFF070D09)else Color(0xFF0B130E),Offset(0f,y),Size(size.width,row));drawLine(Grid,Offset(0f,y+row),Offset(size.width,y+row));drawContext.canvas.nativeCanvas.drawText("${t+1} ${trackName(s,t).take(12)}${if(p.frozen.any{it.track==t})" *" else ""}",8.dp.toPx(),y+27.dp.toPx(),paint)}
                clipRect(left,0f,size.width,size.height){
                    var at=floor(start/p.barBeats)*p.barBeats
                    while(at<=start+span){val x=left+((at-start)/span*width).toFloat();drawLine(Grid,Offset(x,0f),Offset(x,size.height));at+=p.barBeats}
                    for(r in regions){val c=p.clip(r.slot)?:continue;val position=if(drag?.id==r.id)r.beat+dragDelta/width*span else r.beat
                        if(position+r.length<start||position>start+span)continue
                        val x=left+((position-start)/span*width).toFloat();val y=3.dp.toPx();val w=(r.length/span*width).toFloat()
                        drawRoundRect(if(r.id==selected)Color(0xFF2B673B)else Color(0xFF173D23),Offset(x,y),Size(w,row-6.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        clipRect(max(left,x),y,min(size.width,x+w),y+row-6.dp.toPx()){
                            drawContext.canvas.nativeCanvas.drawText(c.name,x+5.dp.toPx(),y+15.dp.toPx(),paint)
                            clipPreview(c,p,Offset(x+2.dp.toPx(),y+22.dp.toPx()),Size(w-4.dp.toPx(),row-32.dp.toPx()),Green,r.offset,r.length,r.audioOffset,s.bpm,r.beat)
                        }
                        if(r.id==selected)drawRect(Green,Offset(x,y),Size(w,row-6.dp.toPx()),style=Stroke(1.dp.toPx()))
                    }
                    val cx=left+((cursor-start)/span*width).toFloat();drawLine(Color.White.copy(alpha=.6f),Offset(cx,0f),Offset(cx,size.height),1.dp.toPx())
                    if(s.stats.playing&&s.sequence.mode==1){val x=left+((beat-start)/span*width).toFloat();drawLine(Green,Offset(x,0f),Offset(x,size.height),2.dp.toPx())}
                }
            }
            }
        }
    }
    if(showRegion&&region!=null)RegionDialog(region,p,s.bpm,{showRegion=false},service)
}
@Composable private fun RegionDialog(r: Region,p: Project,bpm: Float,close: ()->Unit,service: AudioService){
    var at by remember(r.id){mutableStateOf(number(r.beat))};var length by remember(r.id){mutableStateOf(number(r.length))}
    val c=p.clip(r.slot)!!;val a=p.asset(c.asset)
    var frame by remember(r.id){mutableStateOf(((r.length*60/bpm*(a?.rate?:48000))/2).roundToLong().toString())}
    AlertDialog(onDismissRequest=close,title={Text("Arrangement region")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text(c.name,color=Green);NumberField("Start beat · 0-based",at,{at=it});NumberField("Length in beats",length,{length=it})
        if(a!=null){Text("Split at a source-sample offset from this region's start (${a.rate} Hz).",fontSize=12.sp);NumberField("Sample offset",frame,{frame=it});Action("Split at sample",frame.toLongOrNull()?.let{it>0}==true){service.projectEdit{it.splitRegion(r.id,if(c.warp)r.beat+frame.toLong().toDouble()/a.rate*c.sourceBpm/60 else TempoMap.advance(if(it.tempoEnabled)it.tempoPoints else emptyList(),r.beat,frame.toLong().toDouble()/a.rate,bpm),bpm)};close()}}
        Action("Delete region"){service.projectEdit{it.copy(regions=it.regions.filter{v->v.id!=r.id})};close()}
    }},confirmButton={TextButton({service.projectEdit{it.editRegion(r.id,at.toDouble(),length.toDouble())};close()},enabled=at.toDoubleOrNull()?.isFinite()==true&&length.toDoubleOrNull()?.let{it.isFinite()&&it>0}==true){Text("Apply")}},dismissButton={TextButton(close){Text("Cancel")}})
}
