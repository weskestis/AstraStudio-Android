package com.weskestis.astrastudio

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.workspace.StudioWorkspace

class MainActivity : ComponentActivity() {
    private var audioService by mutableStateOf<AudioService?>(null)
    private var bound = false
    private var pendingUri by mutableStateOf<String?>(null)
    private var pendingKind by mutableStateOf("")
    private var pendingSamples by mutableStateOf<List<String>>(emptyList())
    private var importSlot = 0
    private var captureStereo=false
    private var labExportPath=""
    private var labExportFirst=0L
    private var labExportLast=0L
    private val labExport=registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")){receive(it,"labExport")}
    private val captureNotificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){beginCapture()}
    private val microphonePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)requestCaptureNotification()else audioService?.notice("Microphone permission is needed to record. You can allow it in Android Settings → Apps → Astra Studio → Permissions.")}
    private val labImport=registerForActivityResult(ActivityResultContracts.OpenDocument()){receive(it,"lab")}
    private var exportRate = 48000
    private var exportTail = 4f
    private var exportFloating = true
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { beginAudio() }
    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { receive(it,"audio") }
    private val importSamples = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { pendingSamples=it.map(Uri::toString) }
    private val importPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { receive(it,"pack") }
    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { receive(it,"open") }
    private val saveProject = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { receive(it,"save") }
    private val exportWave = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { receive(it,"wave") }
    private fun receive(uri: Uri?, kind: String) { if(uri!=null){pendingKind=kind;pendingUri=uri.toString()} }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { audioService=(binder as AudioService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { audioService=null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {captureStereo=it.getBoolean("captureStereo",false);labExportPath=it.getString("labPath","");labExportFirst=it.getLong("labFirst");labExportLast=it.getLong("labLast"); pendingSamples=it.getStringArrayList("sampleUris")?:emptyList();pendingUri=it.getString("uri");pendingKind=it.getString("kind","");importSlot=it.getInt("slot");exportRate=it.getInt("rate",48000);exportTail=it.getFloat("tail",4f);exportFloating=it.getBoolean("floating",true) }
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(
                primary=Color(0xFF55F27A),onPrimary=Color(0xFF001606),background=Color.Black,surface=Color(0xFF0B120D),
                secondary=Color(0xFFB6F56B),onSecondary=Color.Black,tertiary=Color(0xFF85E494),onTertiary=Color.Black,
                primaryContainer=Color(0xFF12371C),onPrimaryContainer=Color(0xFF97F7AC),
                secondaryContainer=Color(0xFF1A2B10),onSecondaryContainer=Color(0xFFBCF287),
                tertiaryContainer=Color(0xFF0D3216),onTertiaryContainer=Color(0xFFA7F5B8),
                onBackground=Color(0xFFE7F5E9),onSurface=Color(0xFFE7F5E9),surfaceVariant=Color(0xFF102016),
                onSurfaceVariant=Color(0xFFA2BAA7),surfaceTint=Color(0xFF55F27A),outline=Color(0xFF487052),outlineVariant=Color(0xFF26472E))) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    val workspaceState=rememberSaveableStateHolder()
                    val service=audioService
                    if(service==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Opening Astra Studio…")}
                    else workspaceState.SaveableStateProvider("studio") {
                        val state by service.state.collectAsStateWithLifecycle()
                        LaunchedEffect(pendingUri,pendingSamples,state.ready,state.busy) {
                            if(pendingSamples.isNotEmpty()&&state.ready&&!state.busy){service.importSamples(pendingSamples.map(Uri::parse));pendingSamples=emptyList();return@LaunchedEffect}
                            val uri=pendingUri
                            if(uri!=null&&state.ready&&!state.busy){
                                when(pendingKind){"labExport"->service.saveLabWave(Uri.parse(uri),labExportPath,labExportFirst,labExportLast);"lab"->service.importToLab(Uri.parse(uri));"pack"->service.importSamples(listOf(Uri.parse(uri)),true);"audio"->service.importAudio(Uri.parse(uri),importSlot);"open"->service.openProject(Uri.parse(uri));"save"->service.saveProject(Uri.parse(uri));"wave"->service.exportWave(Uri.parse(uri),exportRate,exportTail,exportFloating)}
                                pendingUri=null;pendingKind=""
                            }
                        }
                        StudioWorkspace(state,service,::requestAudio,recordAudio={stereo->captureStereo=stereo;requestCapture()},saveLab={path,name,first,last->labExportPath=path;labExportFirst=first;labExportLast=last;labExport.launch(name+".wav")},importLab={labImport.launch(arrayOf("audio/*"))},
                            importSamples={importSamples.launch(arrayOf("audio/*"))},importPack={importPack.launch(arrayOf("application/zip","application/x-zip-compressed"))},
                            importAudio={slot->importSlot=slot;importAudio.launch(arrayOf("audio/*"))},
                            openProject={openProject.launch(arrayOf("application/octet-stream","application/zip","*/*"))},
                            saveProject={saveProject.launch(it+".astra")},
                            exportWave={name,rate,tail,floating->exportRate=rate;exportTail=tail;exportFloating=floating;exportWave.launch(name+".wav")})
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle){outState.putBoolean("captureStereo",captureStereo);outState.putString("labPath",labExportPath);outState.putLong("labFirst",labExportFirst);outState.putLong("labLast",labExportLast);outState.putStringArrayList("sampleUris",ArrayList(pendingSamples));outState.putString("uri",pendingUri);outState.putString("kind",pendingKind);outState.putInt("slot",importSlot);outState.putInt("rate",exportRate);outState.putFloat("tail",exportTail);outState.putBoolean("floating",exportFloating);super.onSaveInstanceState(outState)}
    override fun onStart(){super.onStart();bound=bindService(Intent(this,AudioService::class.java),connection,Context.BIND_AUTO_CREATE)}
    override fun onStop(){if(isChangingConfigurations)startService(Intent(this,AudioService::class.java));audioService?.releaseTouchNotes();audioService?.stopSelectionPreview();if(bound){unbindService(connection);bound=false};audioService=null;super.onStop()}
    private fun requestCapture(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)microphonePermission.launch(Manifest.permission.RECORD_AUDIO)else requestCaptureNotification()}
    private fun requestCaptureNotification(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)captureNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else beginCapture()}
    private fun beginCapture(){if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))ContextCompat.startForegroundService(this,Intent(this,AudioService::class.java).setAction(AudioService.ACTION_CAPTURE).putExtra("stereo",captureStereo))}
    private fun requestAudio(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else beginAudio()}
    private fun beginAudio(){ContextCompat.startForegroundService(this,Intent(this,AudioService::class.java).setAction(AudioService.ACTION_START))}
}
