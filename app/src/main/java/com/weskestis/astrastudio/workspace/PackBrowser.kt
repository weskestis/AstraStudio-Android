package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.audio.StudioState
import com.weskestis.astrastudio.packs.*
import com.weskestis.astrastudio.project.AudioAsset

@Composable internal fun PackBrowser(s: StudioState,track: Int,slot: Int,pad: Int?,service: AudioService,install: ()->Unit,edit: ()->Unit,editSynth: ()->Unit){
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var query by rememberSaveable{mutableStateOf("")};var pack by rememberSaveable{mutableStateOf("")};var category by rememberSaveable{mutableStateOf("")}
    var favorites by rememberSaveable{mutableStateOf(false)};var selected by rememberSaveable{mutableStateOf("")};var page by rememberSaveable{mutableIntStateOf(0)}
    PanelBackHandler(selected.isNotEmpty()){selected="";service.stopPreview()}
    val catalog=s.packs;val sound=catalog.sound(selected)
    if(sound!=null){
        val collection=catalog.pack(sound)
        LaunchedEffect(selected){service.inspectPackSound(selected)}
        Text(sound.name,color=Green,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("Pack sound details"))
        Text("${collection.name} · ${sound.category}",fontSize=12.sp,color=Muted)
        Text(if(sound.isTable)"${sound.frames/sound.cycle} cycles · ${sound.cycle} points per cycle · original ${sound.rate} Hz WAV" else "${number(sound.seconds)} s · ${sound.rate} Hz · ${if(sound.channels==2)"stereo" else "mono"}",fontSize=12.sp,color=Muted)
        if(sound.bpm>0||sound.key.isNotBlank())Text(listOfNotNull(if(sound.bpm>0)"${number(sound.bpm)} BPM"else null,sound.key.takeIf{it.isNotBlank()}?.let{"Key $it (filename)"}).joinToString(" · "),color=Green,fontSize=12.sp)
        val detail=s.packDetail?.takeIf{it.key==selected}
        if(detail!=null)SampleWaveform(AudioAsset(0,sound.name,"preview.wav",sound.rate,sound.frames,detail.waveform))else Text("Reading waveform…",color=Muted)
        Tools{Action("▶ Preview",!s.busy,"Preview pack sound"){service.previewPackSound(selected)};Action("Stop preview"){service.stopPreview()};FilterChip(selected in catalog.favorites,{service.packFavorite(selected)},label={Text(if(selected in catalog.favorites)"★ Favorite"else "☆ Favorite")})}
        if(sound.isTable){
            Tools{Action("Load oscillator A",!s.busy,"Load table A"){service.usePackSound(selected,track,slot,17)};Action("Load oscillator B",!s.busy,"Load table B"){service.usePackSound(selected,track,slot,18)}}
            Text("All cycles remain available to position, FM, warp, filters and LFO modulation. Loading selects the wavetable synth and retains its other settings; raise that oscillator's level if it is muted.",fontSize=12.sp,color=Muted)
            Action("Edit synth / modulation",!s.busy,click=editSynth)
        }else{
            Tools{Action(if(pad!=null&&pad>=0)"Use on pad ${pad+1}"else "Use in sampler",!s.busy,"Use pack sound"){service.usePackSound(selected,track,slot,pad?:-1)}
                Action("Add audio clip",!s.busy,"Pack audio clip"){service.usePackSound(selected,track,slot,16)}
                Action("Add to project samples",!s.busy){service.usePackSound(selected,track,slot,19)}}
            Action("Edit sampler / drums",!s.busy,click=edit)
            Text("Trim, reverse, loop, pitch and envelopes are in Sampler / drums. Audio clips have waveform editing and Warp; Sample Lab can zoom, analyze and extract project sounds. Filename keys assume octave 4 in the sampler; adjust Root MIDI note as needed.",fontSize=12.sp,color=Muted)
        }
        Text(s.message,color=Green,fontSize=13.sp,modifier=Modifier.padding(vertical=10.dp))
        Text("Loaded sounds are independent project copies. Undo restores the previous assignment; portable projects carry their audio. This browser stays open until Back or Close.",fontSize=12.sp,color=Muted)
        Text("Creator: ${collection.creator.ifBlank{collection.name}}",fontSize=12.sp,color=Muted)
        return
    }
    Text("SOUND PACKS · ${catalog.sounds.size} sounds",fontWeight=FontWeight.Bold,color=Green,modifier=Modifier.testTag("Sound pack browser"))
    Tools{Action("Install pack parts",!s.busy,"Install sound packs",install);FilterChip(favorites,{favorites=!favorites;page=0},label={Text("★ Favorites")})}
    Text("Select all your .riftpack parts together, or a WAV ZIP. Installed packs stay available across projects. Only sounds you load use the project's 64 source slots.",fontSize=12.sp,color=Muted)
    if(catalog.warning.isNotBlank())Text(catalog.warning,color=Green)
    if(catalog.packs.isEmpty()){
        Text("Your Rift collection: MAYGE Arcane (2 parts), Ember Riddim (2), MAG Trance (2), Trance Starter (1). All seven parts contain 1,194 samples and 6 wavetables.",fontSize=13.sp,modifier=Modifier.padding(vertical=12.dp))
        Text("Install the .riftpack files you downloaded for Rift directly. Original multipart RARs and external Serum/Vital/Reason/Ableton presets are not supported pack files.",fontSize=12.sp,color=Muted)
    }else{
        val collections=catalog.packs.groupBy{it.id}
        Text(collections.values.joinToString("\n"){p->"${p[0].name}: ${p.size}/${p[0].parts} parts · ${p.sumOf{it.sounds.size}} sounds"},fontSize=12.sp,color=Muted)
    }
    OutlinedTextField(query,{query=it.take(160);page=0},label={Text("Search packs, sounds, BPM or key")},singleLine=true,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),
        modifier=Modifier.fillMaxWidth().testTag("Pack search"))
    PackFilter("Pack",pack,catalog.packs.distinctBy{it.id}.map{it.id to it.name}){pack=it;category="";page=0}
    val all=remember(catalog.packs){catalog.sounds}
    PackFilter("Category",category,all.filter{pack.isBlank()||it.packId==pack}.map{it.category}.distinct().sorted().map{it to it}){category=it;page=0}
    val matches=remember(all,pack,category,query,favorites,catalog.favorites){
        val names=catalog.packs.associate{it.id to it.name};val terms=query.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
        all.filter{a->(pack.isBlank()||a.packId==pack)&&(category.isBlank()||a.category==category)&&(!favorites||a.keyId in catalog.favorites)&&terms.all{term->"${a.name} ${a.category} ${a.key} ${a.bpm} ${names[a.packId]}".contains(term,true)}}.sortedWith(compareBy({it.name},{it.packId}))
    }
    val pages=maxOf(1,(matches.size+39)/40);val current=page.coerceIn(0,pages-1)
    Text("${matches.size} matches · page ${current+1}/$pages",fontSize=12.sp,color=Muted,modifier=Modifier.testTag("Pack result count"))
    Tools{Action("Previous",current>0){page=current-1};Action("Next",current+1<pages){page=current+1}}
    matches.drop(current*40).take(40).forEach{a->OutlinedButton({focus.clearFocus();keyboard?.hide();service.stopPreview();selected=a.keyId},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("Pack sound ${a.keyId}")){
        Column(Modifier.fillMaxWidth()){Text((if(a.keyId in catalog.favorites)"★ "else "")+a.name,maxLines=2);Text("${a.category} · ${if(a.isTable)"Wavetable"else "${number(a.seconds)} s"}",fontSize=11.sp,color=Muted)}
    }}
    if(matches.isEmpty())Text("No matching sounds. Install a pack or change the filters.",color=Muted,modifier=Modifier.padding(vertical=12.dp))
}

@Composable private fun PackFilter(label: String,value: String,choices: List<Pair<String,String>>,change: (String)->Unit){
    var open by remember{mutableStateOf(false)}
    Box{TextButton({open=true},modifier=Modifier.testTag("$label filter")){Text("$label: ${choices.firstOrNull{it.first==value}?.second?:"All"}")}
        DropdownMenu(open,{open=false}){DropdownMenuItem({Text("All")},{change("");open=false});choices.forEach{(id,name)->DropdownMenuItem({Text(name,maxLines=2)},{change(id);open=false})}}}
}

@Composable internal fun ImportedTables(s: StudioState,track: Int,service: AudioService,browse: ()->Unit){
    val tables=s.project.tables[track]
    if(tables.any{it>=0}){
        Text("IMPORTED WAVETABLES",color=Green,fontWeight=FontWeight.Bold)
        tables.forEachIndexed{osc,id->if(id>=0){Text("Osc ${if(osc==0)"A"else "B"}: ${s.project.asset(id)?.name}",fontSize=12.sp);Action("Use factory bank ${if(osc==0)"A"else "B"}"){service.clearWavetable(track,osc)}}}
        Text("Imported tables replace that oscillator's factory bank. Position, tuning, warp and modulation below remain active.",fontSize=12.sp,color=Muted)
    }
    Action("Browse sound packs / wavetables",!s.busy,click=browse)
}
