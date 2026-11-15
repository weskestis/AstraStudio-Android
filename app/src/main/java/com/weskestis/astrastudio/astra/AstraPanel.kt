package com.weskestis.astrastudio.astra

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.audio.StudioState
import com.weskestis.astrastudio.project.ProjectDocument
import com.weskestis.astrastudio.workspace.Action
import com.weskestis.astrastudio.workspace.Tools
import com.weskestis.astrastudio.workspace.Green
import com.weskestis.astrastudio.workspace.Muted

@Composable
fun AstraPanel(state: StudioState, service: AudioService, selected: Int) {
    val a=state.astra;val context=LocalContext.current;val keyboard=LocalSoftwareKeyboardController.current;val clipboard=LocalClipboardManager.current
    var prompt by rememberSaveable{mutableStateOf("")}
    var settings by rememberSaveable{mutableStateOf(false)}
    var showJson by rememberSaveable{mutableStateOf(false)}
    var history by rememberSaveable{mutableStateOf(false)}
    com.weskestis.astrastudio.workspace.PanelBackHandler(settings||showJson||history){when{history->history=false;showJson->showJson=false;else->settings=false}}
    var json by remember{mutableStateOf("")}
    // Never save credentials in the Activity saved-state bundle or project file.
    var key by remember{mutableStateOf("")}
    val audioPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)service.astraAttach(uri)}
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK){result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let{prompt=it.take(8000);service.astraNotice("Speech captured. Review the words, then choose Offline preview or Ask Gemini.")}}
        else service.astraNotice("Voice input cancelled. You can type instead.")
    }
    fun link(url: String){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{service.astraNotice("Could not open a browser on this device.")}}
    Text("ASTRA / MAKE THE NEXT MOVE",color=Green,fontWeight=FontWeight.Bold,fontSize=17.sp)
    Text("Track ${selected%16+1} · scene ${selected/16+1}. Preview edits, apply once, undo the whole proposal.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=6.dp))
    Tools{
        Action("Gemini settings",!a.working,"Astra settings"){settings=!settings;showJson=false;history=false;key=""}
        Action("Command JSON",!a.working,"Astra JSON"){showJson=!showJson;settings=false;history=false}
        Action("Copy for Gemini",!a.working,"Astra handoff"){
            val d=ProjectDocument(state.project,state.bpm,state.master,state.limiterDrive,state.limiterRelease,state.tracks,state.patches,state.effects)
            clipboard.setText(AnnotatedString(GeminiProtocol.handoff(prompt,d,selected)))
            service.astraNotice("Copied a prompt with project details. Paste it into Gemini, attach audio there if wanted, then paste its JSON into Command JSON. No API key is needed for this manual route.")
        }
        Action("Recent feedback"){history=!history;settings=false;showJson=false}
    }
    if(settings){
        Text("Gemini · ${GeminiProtocol.MODEL}",color=Green,modifier=Modifier.padding(top=10.dp))
        Text("Use your own key from Google AI Studio. For free-tier use, choose a project without Cloud Billing. Quotas and model availability are set by Google; Astra cannot verify your billing tier. A paid project can incur charges.",fontSize=12.sp)
        Text("Requests send your text, a project summary and any attached audio to Google. Free-tier content may be used to improve Google's products. Nothing is sent until you tap Ask Gemini. Your key stays encrypted on this phone and is excluded from project exports.",fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))
        Text(if(a.keySaved)"A key is saved on this phone." else "No key saved. Offline commands work without one.",color=Muted,fontSize=12.sp,modifier=Modifier.testTag("Astra key status"))
        OutlinedTextField(key,{if(it.length<=256)key=it},label={Text("Google AI Studio API key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}),modifier=Modifier.fillMaxWidth().testTag("Astra key input"))
        Tools{
            Action("Save key",key.isNotBlank()&&!a.working,"Astra save key"){val entered=key;key="";keyboard?.hide();service.astraSaveKey(entered)}
            Action("Remove key",a.keySaved&&!a.working,"Astra remove key"){key="";service.astraRemoveKey()}
            Action("Get free-tier key"){link("https://aistudio.google.com/api-keys")}
            Action("Google pricing"){link("https://ai.google.dev/gemini-api/docs/pricing")}
        }
    }
    OutlinedTextField(prompt,{if(it.length<=8000)prompt=it},label={Text("Ask Astra")},placeholder={Text("Write a 145 BPM riddim bassline in F minor…")},minLines=3,maxLines=7,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}),modifier=Modifier.fillMaxWidth().padding(top=10.dp).testTag("Astra prompt"))
    Tools{
        Action("Speak",!a.working,"Astra speak"){
            keyboard?.hide()
            try{voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_PROMPT,"Tell Astra what to make"))}
            catch(e: ActivityNotFoundException){service.astraNotice("No speech input app is installed. Use your keyboard's microphone or type the command.")}
            catch(e: SecurityException){service.astraNotice("Speech input is unavailable. Use your keyboard's microphone or type instead.")}
        }
        Action("Attach audio",!a.working,"Astra attach"){audioPicker.launch(arrayOf("audio/*"))}
        if(a.audioName.isNotEmpty())Action("Remove audio",!a.working){service.astraRemoveAudio()}
    }
    if(a.audioName.isNotEmpty())Text("Attached: ${a.audioName} · ${a.audioBytes/1024} KiB. Sent only with Ask Gemini.",fontSize=12.sp,color=Green)
    else Text("Gemini can listen to an attached file up to 8 MiB. Use MP3 for longer songs. It receives no live engine audio automatically.",fontSize=11.sp,color=Muted)
    Tools{
        Button({keyboard?.hide();service.astraRequest(prompt,selected,false)},enabled=!a.working&&!state.busy&&prompt.isNotBlank(),modifier=Modifier.heightIn(min=48.dp).testTag("Astra offline")){Text("Offline preview")}
        OutlinedButton({keyboard?.hide();service.astraRequest(prompt,selected,true)},enabled=!a.working&&!state.busy&&(prompt.isNotBlank()||a.audioName.isNotEmpty()),modifier=Modifier.heightIn(min=48.dp).testTag("Astra Gemini")){Text("Ask Gemini")}
        if(a.working)Action("Cancel",tag="Astra cancel"){service.astraCancel()}
    }
    if(a.working)LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=6.dp))
    Text(a.status,color=Green,fontSize=13.sp,modifier=Modifier.padding(vertical=8.dp).testTag("Astra status"))
    if(a.summary.isNotEmpty()){
        HorizontalDivider();Text(a.summary,fontSize=14.sp,modifier=Modifier.padding(vertical=10.dp).testTag("Astra summary"))
        if(a.changes.isNotEmpty()){
            Text("PROPOSED CHANGES",color=Muted,fontSize=11.sp)
            a.changes.forEachIndexed{i,line->Text("${i+1}. $line",fontSize=13.sp,modifier=Modifier.padding(vertical=4.dp))}
            val stale=a.proposal!=0L&&a.revision!=state.revision
            if(stale)Text("Project changed since this preview. Generate a new proposal to keep your latest edits.",color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.testTag("Astra stale"))
            Tools{
                Button({keyboard?.hide();service.astraApply(a.proposal)},enabled=a.proposal!=0L&&!stale&&!a.working&&!state.busy&&state.recording<0,modifier=Modifier.heightIn(min=48.dp).testTag("Astra apply")){Text("Apply edits")}
                Action("Undo",state.canUndo&&!a.working&&!state.busy,"Astra undo"){service.undo()}
                Action("Dismiss",!a.working,"Astra dismiss"){service.astraDismiss()}
            }
        }
        Action("Copy proposal JSON",a.json.isNotBlank(),"Astra copy JSON"){clipboard.setText(AnnotatedString(a.json));service.astraNotice("Proposal JSON copied. It contains no API key or audio bytes.")}
    }
    if(showJson){
        OutlinedTextField(json,{if(it.length<=262144)json=it},label={Text("Paste Astra command JSON")},minLines=4,maxLines=10,modifier=Modifier.fillMaxWidth().testTag("Astra JSON input"))
        Action("Validate JSON",json.isNotBlank()&&!a.working&&!state.busy,"Astra validate JSON"){keyboard?.hide();service.astraJson(json)}
    }
    Text("TRY AN OFFLINE COMMAND",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=14.dp))
    OfflineCommands.examples.forEachIndexed{i,example->TextButton({prompt=example},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("Astra example $i")){Text(example,fontSize=13.sp)}}
    if(history){Text("Recent feedback stays in this session only.",fontSize=11.sp,color=Muted);a.history.forEach{Text(it,fontSize=12.sp,modifier=Modifier.padding(vertical=5.dp))}}
}
