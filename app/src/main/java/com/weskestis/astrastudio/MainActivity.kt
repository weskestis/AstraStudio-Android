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
    private var importSlot = 0
    private var exportRate = 48000
    private var exportTail = 4f
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { beginAudio() }
    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { receive(it,"audio") }
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
        savedInstanceState?.let { pendingUri=it.getString("uri");pendingKind=it.getString("kind","");importSlot=it.getInt("slot");exportRate=it.getInt("rate",48000);exportTail=it.getFloat("tail",4f) }
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
                    val service=audioService
                    if(service==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Opening Astra Studio…")}
                    else {
                        val state by service.state.collectAsStateWithLifecycle()
                        LaunchedEffect(pendingUri,state.ready,state.busy) {
                            val uri=pendingUri
                            if(uri!=null&&state.ready&&!state.busy){
                                when(pendingKind){"audio"->service.importAudio(Uri.parse(uri),importSlot);"open"->service.openProject(Uri.parse(uri));"save"->service.saveProject(Uri.parse(uri));"wave"->service.exportWave(Uri.parse(uri),exportRate,exportTail)}
                                pendingUri=null;pendingKind=""
                            }
                        }
                        StudioWorkspace(state,service,::requestAudio,
                            importAudio={slot->importSlot=slot;importAudio.launch(arrayOf("audio/*"))},
                            openProject={openProject.launch(arrayOf("application/octet-stream","application/zip","*/*"))},
                            saveProject={saveProject.launch(it+".astra")},
                            exportWave={name,rate,tail->exportRate=rate;exportTail=tail;exportWave.launch(name+".wav")})
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle){outState.putString("uri",pendingUri);outState.putString("kind",pendingKind);outState.putInt("slot",importSlot);outState.putInt("rate",exportRate);outState.putFloat("tail",exportTail);super.onSaveInstanceState(outState)}
    override fun onStart(){super.onStart();bound=bindService(Intent(this,AudioService::class.java),connection,Context.BIND_AUTO_CREATE)}
    override fun onStop(){if(isChangingConfigurations)startService(Intent(this,AudioService::class.java));audioService?.releaseTouchNotes();if(bound){unbindService(connection);bound=false};audioService=null;super.onStop()}
    private fun requestAudio(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else beginAudio()}
    private fun beginAudio(){ContextCompat.startForegroundService(this,Intent(this,AudioService::class.java).setAction(AudioService.ACTION_START))}
}
