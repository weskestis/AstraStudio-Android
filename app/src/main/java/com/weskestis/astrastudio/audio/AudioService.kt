package com.weskestis.astrastudio.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import com.weskestis.astrastudio.project.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import androidx.core.content.ContextCompat
import com.weskestis.astrastudio.MainActivity
import com.weskestis.astrastudio.R
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.fx.FxStats
import com.weskestis.astrastudio.fx.FxSchema
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.synth.SynthSchema
import com.weskestis.astrastudio.midi.MidiInputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackControls(val gain: Float = 0.8f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false, val armed: Boolean = false, val sendA: Float = 0f, val sendB: Float = 0f, val name: String = "")
data class StudioState(
    val project: Project = Project(),
    val sequence: SequenceStats = SequenceStats(),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val work: String = "",
    val progress: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val recording: Int = -1,
    val overdub: Boolean = true,
    val stats: AudioStats = AudioStats(),
    val message: String = "Start audio to play your synth patches.",
    val bpm: Float = 145f,
    val master: Float = 0.7f,
    val bufferBursts: Int = 3,
    val metronome: Boolean = false,
    val tracks: List<TrackControls> = List(AudioBridge.TRACK_COUNT) { TrackControls() },
    val midiDevices: Int = 0,
    val effects: List<FxConfig> = List(AudioBridge.TRACK_COUNT) { FxConfig.initial() },
    val fxStats: FxStats = FxStats(),
    val limiterDrive: Float = 0f,
    val limiterRelease: Float = 80f,
    val meterTrack: Int = 16,
    val patches: List<SynthPatch> = List(AudioBridge.TRACK_COUNT) { SynthPatch.initial() },
)

class AudioService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@AudioService }
    private val binder = LocalBinder()
    private val publishedState = MutableStateFlow(StudioState())
    val state = publishedState.asStateFlow()
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var controlSnapshot = StudioState()
    // Control reads never wait for the UI. Publication is marshalled onto Main,
    // including when an instrumented collector uses an unconfined dispatcher.
    private val mutableState = object {
        var value: StudioState
            get() = controlSnapshot
            set(next) {
                controlSnapshot = next
                uiHandler.post { publishedState.value = next }
            }
    }
    private lateinit var thread: HandlerThread
    private lateinit var control: Handler
    private lateinit var bridge: AudioBridge
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var midi: MidiInputManager? = null
    private var presets = emptyList<SynthPatch>()
    private var fxPresets = emptyList<FxConfig>()
    private lateinit var files: ProjectFiles
    private val io = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val history = ProjectHistory()
    @Volatile private var destroyed = false
    private var playingIntent = false
    private var sent: ProjectDocument? = null
    private var pendingLaunch: Triple<Int,Int,Boolean>? = null
    private val heldTouch = mutableMapOf<Long,Pair<Int,Int>>()
    private data class Recorded(val track: Int,val pitch: Int,val velocity: Float,val absolute: Double,val local: Double)
    private val recorded = mutableMapOf<Long,Recorded>()
    private val saveProject = Runnable {
        val document = document()
        io.execute { try { files.save(document) } catch (e: Exception) { control.post { message("Could not autosave: ${e.message}") } } }
    }
    private fun document() = mutableState.value.let { ProjectDocument(it.project,it.bpm,it.master,it.limiterDrive,it.limiterRelease,it.tracks,it.patches,it.effects) }
    private fun message(text: String) { mutableState.value=mutableState.value.copy(message=text) }
    private fun adopt(d: ProjectDocument) {
        mutableState.value=mutableState.value.copy(project=d.project,bpm=d.bpm,master=d.master,limiterDrive=d.limiterDrive,limiterRelease=d.limiterRelease,
            tracks=d.tracks,patches=d.patches,effects=d.effects,canUndo=history.canUndo,canRedo=history.canRedo)
        control.removeCallbacks(saveProject);control.postDelayed(saveProject,500)
    }
    private fun commit(key: String="", edit: (ProjectDocument)->ProjectDocument) {
        if(!mutableState.value.ready||mutableState.value.busy)return
        try { val old=document();val next=edit(old);require(next.valid()){ "That edit exceeds the project limits or overlaps a region." }
            history.commit(old,next,key);adopt(next);syncEngine()
        } catch(e: Exception) { message(e.message?:"Could not apply edit.") }
    }
    @Volatile private var wantsAudio = false
    private var reconnectAttempts = 0
    private var nextReconnectAt = 0L
    private var stablePolls = 0
    private var nextDemoId = 100_000
    private var nextTapTime = 0L
    private val tapIntervals = ArrayDeque<Long>()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                control.post { stopAudio("Headphones disconnected. Tap Start audio when ready.") }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        files = ProjectFiles(this)
        thread = HandlerThread("AstraControl").also { it.start() }
        control = Handler(thread.looper)
        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    stopAudio("Audio paused by another app. Tap Start audio to resume.")
                }
            }, control)
            .build()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Audio playback", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        control.post {
            bridge = AudioBridge()
            presets = SynthSchema.presets.indices.map(bridge::preset)
            val saved = getSharedPreferences("synth-v1", MODE_PRIVATE)
            mutableState.value = mutableState.value.copy(patches = List(AudioBridge.TRACK_COUNT) { index ->
                SynthPatch.decode(saved.getString("track-$index", null)) ?: presets[when (index) { 0 -> 1; 1 -> 3; 2 -> 5; else -> 0 }]
            })
            fxPresets = FxSchema.presets.indices.map(bridge::fxPreset)
            val savedFx = getSharedPreferences("fx-v1", MODE_PRIVATE)
            mutableState.value = mutableState.value.copy(
                effects = List(AudioBridge.TRACK_COUNT) { index -> FxConfig.decode(savedFx.getString("track-$index", null)) ?: fxPresets[0] },
                limiterDrive = savedFx.getFloat("limiter-drive", 0f).takeIf { it.isFinite() }?.coerceIn(0f,24f) ?: 0f,
                limiterRelease = savedFx.getFloat("limiter-release", 80f).takeIf { it.isFinite() }?.coerceIn(20f,500f) ?: 80f,
            )
            mutableState.value=mutableState.value.copy(busy=true,work="Opening project")
            val fallback=document()
            io.execute {
                var restored: ProjectDocument?=null;var error: String?=null
                try { restored=files.load();if(restored!=null)loadAssets(restored) } catch(e: Exception){restored=null;error=e.message;runCatching{files.preserveFailedAutosave()};bridge.loadAssets(emptyList())}
                control.post { if(!destroyed){if(restored!=null)adopt(restored);else adopt(fallback)
                    mutableState.value=mutableState.value.copy(ready=true,busy=false,work="",message=error?.let{"Could not restore project: $it"}?:"Ready. Start audio, then launch a clip or build an arrangement.")
                    control.post(poll)
                } }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                control.post { startAudio() }
            }
            ACTION_WORK -> {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or (if(wantsAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0))
                if(!mutableState.value.busy&&!wantsAudio){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
            ACTION_STOP -> control.post { stopAudio("Audio stopped.") }
        }
        // Never unexpectedly restart playback after process death.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, AudioService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_astra)
            .setContentTitle("Astra Studio")
            .setContentText(if(mutableState.value.busy)mutableState.value.work else "Audio engine active • tap to return")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop audio", stop).build())
            .build()
    }

    private fun startAudio() {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopAudio("Audio focus unavailable. Try again after the other app stops.")
            return
        }
        wantsAudio = true
        reconnectAttempts = 0
        stablePolls = 0
        val result = bridge.start(mutableState.value.bufferBursts)
        if (result != 0) { stopAudio("Could not open audio: $result"); return }
        applyMix()
        if (midi == null) {
            midi = MidiInputManager(this, control, bridge, { track,key,velocity,id,on -> recordEvent(track,key,velocity,id,on) }) { count ->
                mutableState.value = mutableState.value.copy(midiDevices = count)
            }.also { it.start() }
        }
        mutableState.value = mutableState.value.copy(message = "Engine ready. Launch clips, record MIDI, or play the arrangement.")
    }

    private fun applyMix() { sent=null;syncEngine();bridge.metronome(mutableState.value.metronome);bridge.meterTrack(mutableState.value.meterTrack) }
    private fun syncEngine(): Boolean {
        if(!wantsAudio)return true
        val next=document();val previous=sent;var ok=true
        fun accepted(value: Boolean) { if(!value)ok=false }
        if(previous?.bpm!=next.bpm)accepted(bridge.tempo(next.bpm))
        if(previous?.master!=next.master)accepted(bridge.masterGain(next.master))
        if(previous?.limiterDrive!=next.limiterDrive)accepted(bridge.limiterDrive(next.limiterDrive))
        if(previous?.limiterRelease!=next.limiterRelease)accepted(bridge.limiterRelease(next.limiterRelease))
        for(t in 0..15){
            if(previous?.patches?.get(t)!=next.patches[t])accepted(bridge.patch(t,next.patches[t]))
            if(previous?.effects?.get(t)!=next.effects[t])accepted(bridge.effects(t,next.effects[t]))
            if(previous?.tracks?.get(t)!=next.tracks[t]){val v=next.tracks[t]
                accepted(bridge.trackGain(t,v.gain));accepted(bridge.trackPan(t,v.pan));accepted(bridge.trackMute(t,v.mute));accepted(bridge.trackSolo(t,v.solo));accepted(bridge.sendA(t,v.sendA));accepted(bridge.sendB(t,v.sendB))
            }
        }
        if(previous?.project!=next.project)accepted(bridge.project(next.project))
        if(ok)sent=next
        return ok
    }

    private fun stopAudio(message: String) {
        finishRecording()
        wantsAudio = false
        playingIntent=false;pendingLaunch=null;sent=null
        midi?.close()
        midi = null
        bridge.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
        mutableState.value = mutableState.value.copy(stats = AudioStats(), fxStats = FxStats(), message = message, midiDevices = 0)
        if(mutableState.value.busy)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    }

    private val poll = object : Runnable {
        override fun run() {
            if(wantsAudio&&mutableState.value.ready) {
                val synced=syncEngine()
                if(synced&&pendingLaunch!=null){val (track,scene,all)=pendingLaunch!!;pendingLaunch=null
                    if(!playingIntent){bridge.transport(true);playingIntent=true}
                    if(all)bridge.scene(scene,mutableState.value.project.launchBeats.toFloat()) else bridge.launch(track,scene,mutableState.value.project.launchBeats.toFloat())
                }
            }
            val stats = bridge.stats()
            mutableState.value = mutableState.value.copy(stats = stats, fxStats = if (stats.running) bridge.fxStats() else FxStats(), sequence=if(stats.running)bridge.sequence() else SequenceStats(), progress=if(mutableState.value.work=="Rendering WAV")bridge.exportProgress() else mutableState.value.progress)
            if (wantsAudio && stats.running) {
                if (++stablePolls >= 100) reconnectAttempts = 0
            } else stablePolls = 0
            if (wantsAudio && stats.error != 0) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextReconnectAt) {
                    if (reconnectAttempts >= 3) stopAudio("Audio route unavailable (${stats.error}). Tap Start audio to retry.")
                    else {
                        reconnectAttempts++
                        nextReconnectAt = now + 1000L
                        finishRecording();playingIntent=false;pendingLaunch=null
                        bridge.stop()
                        val result = bridge.start(mutableState.value.bufferBursts)
                        if (result == 0) {
                            applyMix()
                            mutableState.value = mutableState.value.copy(message = "Audio route reopened. Playback stopped; ready to play.")
                        } else mutableState.value = mutableState.value.copy(message = "Reopening audio route: attempt $reconnectAttempts / 3")
                    }
                }
            }
            control.postDelayed(this, 50)
        }
    }

    fun stopFromUi() { control.post { stopAudio("Audio stopped.") } }
    fun panic() { control.post { finishRecording();bridge.panic();playingIntent=false;pendingLaunch=null;message("All notes and effect tails cleared.") } }
    fun audition(track: Int, enabled: Boolean) { control.post { if(enabled&&wantsAudio)bridge.noteOn(track,29,.8f,20000+track)else bridge.noteOff(track,20000+track) } }
    fun noteOn(track: Int,note: Int,id: Int,velocity: Float=.85f) { control.post {
        if(wantsAudio&&bridge.noteOn(track,note,velocity,id)){heldTouch[(track.toLong() shl 32)+id]=track to id;recordEvent(track,note,velocity,id,true)}
    } }
    fun noteOff(track: Int,id: Int) { control.post {heldTouch.remove((track.toLong() shl 32)+id);recordEvent(track,0,0f,id,false);bridge.noteOff(track,id)} }
    fun releaseTouchNotes() { control.post { heldTouch.values.toList().forEach{(track,id)->recordEvent(track,0,0f,id,false);bridge.noteOff(track,id)};heldTouch.clear();for(t in 0..15)bridge.noteOff(t,20000+t) } }
    fun transport() { control.post { if(playingIntent)stopTransport()else playNow(mutableState.value.project.mode,0.0) } }
    private fun stopTransport(){finishRecording();bridge.transport(false);playingIntent=false;pendingLaunch=null}
    fun stopTransportFromUi(){control.post{stopTransport()}}
    fun play(mode: Int,beat: Double=0.0){control.post{if(wantsAudio&&!mutableState.value.busy)playNow(mode,beat)}}
    private fun playNow(mode: Int,beat: Double){
        finishRecording();commit{it.copy(project=it.project.copy(mode=mode))}
        if(!syncEngine()){message("Playback is updating; try Play again.");return}
        bridge.transport(true);playingIntent=true
        if(mode==1)bridge.seek(beat)else bridge.scene(0,0f)
    }
    fun launch(slot: Int){control.post{if(!wantsAudio||mutableState.value.busy)return@post;finishRecording();commit{it.copy(project=it.project.copy(mode=0))};pendingLaunch=Triple(slot%16,slot/16,false)}}
    fun launchScene(scene: Int){control.post{if(!wantsAudio||mutableState.value.busy)return@post;finishRecording();commit{it.copy(project=it.project.copy(mode=0))};pendingLaunch=Triple(0,scene,true)}}
    fun stopTrack(track: Int){control.post{if(wantsAudio)bridge.launch(track,-1,mutableState.value.project.launchBeats.toFloat())}}
    fun seek(beat: Double){control.post{if(beat.isFinite()&&beat in 0.0..65536.0)bridge.seek(beat)}}
    fun master(value: Float){control.post{if(value.isFinite())commit("master"){it.copy(master=value.coerceIn(0f,1f))}}}
    fun tempo(value: Float){control.post{setTempo(value)}}
    private fun setTempo(value: Float){if(value.isFinite())commit("tempo"){it.copy(bpm=value.coerceIn(30f,300f))}}
    fun tapTempo() { control.post {
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = now - nextTapTime
        nextTapTime = now
        if (gap !in 200..2000) tapIntervals.clear()
        else {
            tapIntervals.addLast(gap)
            while (tapIntervals.size > 4) tapIntervals.removeFirst()
            setTempo((60000.0 / tapIntervals.average()).toFloat())
        }
    } }
    fun metronome(value: Boolean) { control.post {
        mutableState.value = mutableState.value.copy(metronome = value)
        bridge.metronome(value)
    } }
    fun buffer(bursts: Int) { control.post {
        if (bursts in 2..8) {
            mutableState.value = mutableState.value.copy(bufferBursts = bursts)
            val result = bridge.setBufferBursts(bursts)
            if (wantsAudio && result < 0) mutableState.value = mutableState.value.copy(message = "This output route did not accept a buffer change ($result).")
        }
    } }
    fun track(index: Int,value: TrackControls){control.post{if(index in 0..15)commit("track-$index"){it.copy(tracks=it.tracks.toMutableList().also{list->list[index]=value})}}}
    private fun editPatch(track: Int,transform: (SynthPatch)->SynthPatch){if(track in 0..15)commit("patch-$track"){it.copy(patches=it.patches.toMutableList().also{p->p[track]=transform(p[track])})}}
    fun preset(track: Int, index: Int) { control.post {
        if (index in presets.indices) editPatch(track) { presets[index] }
    } }
    fun synthParameter(track: Int, id: Int, value: Float) { control.post { editPatch(track) { it.parameter(id, value) } } }
    fun synthRoute(track: Int, slot: Int, field: Int, value: Float) { control.post { editPatch(track) { it.route(slot, field, value) } } }
    fun synthShape(track: Int, lfo: Int, kind: Int) { control.post { editPatch(track) { it.shape(lfo, kind) } } }
    fun paintLfo(track: Int, lfo: Int, from: Int, fromValue: Float, to: Int, toValue: Float) {
        control.post { editPatch(track) { it.paint(lfo, from, fromValue, to, toValue) } }
    }

    private fun editEffects(track: Int,transform: (FxConfig)->FxConfig){if(track in 0..15)commit("fx-$track"){it.copy(effects=it.effects.toMutableList().also{p->p[track]=transform(p[track])})}}
    fun fxPreset(track: Int, index: Int) { control.post { if (index in fxPresets.indices) editEffects(track) { fxPresets[index] } } }
    fun fxParameter(track: Int, id: Int, value: Float) { control.post { editEffects(track) { it.parameter(id, value) } } }
    fun fxMove(track: Int, unit: Int, direction: Int) { control.post { editEffects(track) { it.move(unit, direction) } } }
    fun fxLane(track: Int, unit: Int, lane: Int) { control.post { editEffects(track) { it.lane(unit, lane) } } }
    fun fxMacro(track: Int, macro: Int, field: Int, value: Float) { control.post { editEffects(track) { it.macro(macro, field, value) } } }
    fun meterTrack(track: Int) { control.post {
        if (track in 0..16) { mutableState.value = mutableState.value.copy(meterTrack = track); bridge.meterTrack(track) }
    } }
    fun limiterDrive(value: Float){control.post{if(value.isFinite())commit("limiter-drive"){it.copy(limiterDrive=value.coerceIn(0f,24f))}}}
    fun limiterRelease(value: Float){control.post{if(value.isFinite())commit("limiter-release"){it.copy(limiterRelease=value.coerceIn(20f,500f))}}}

    /** Two sample-scheduled bars using the selected track's current synth. */
    fun demo(track: Int) { control.post {
        if (track !in mutableState.value.tracks.indices) return@post
        val stats = bridge.stats()
        if (!stats.running) return@post
        finishRecording();bridge.transport(false);playingIntent=false;pendingLaunch=null
        val base = stats.frame + stats.sampleRate / 5
        val step = stats.sampleRate * 60.0 / mutableState.value.bpm / 2.0
        val notes = intArrayOf(29, 29, 36, 29, 32, 29, 39, 36, 29, 41, 36, 32, 29, 36, 39, 29)
        notes.forEachIndexed { i, note ->
            val id = nextDemoId++
            val at = base + (step * i).toLong()
            bridge.noteOn(track, note, 0.9f, id, at)
            bridge.noteOff(track, id, at + (step * 0.7).toLong())
        }
        mutableState.value = mutableState.value.copy(message = "Two-bar F-minor phrase • Track ${track + 1} • ${mutableState.value.patches[track].name}")
    } }

    fun projectEdit(key: String="",edit: (Project)->Project){control.post{commit(key){it.copy(project=edit(it.project))}}}
    fun clipEdit(slot: Int,key: String="",edit: (Clip)->Clip){projectEdit(key){p->val clip=p.clip(slot)?:error("Choose a clip.");p.put(edit(clip))}}
    fun newClip(slot: Int){projectEdit{p->require(p.clip(slot)==null){"That slot already has a clip."};p.put(Clip(slot,"MIDI ${slot/16+1}",p.barBeats*4))}}
    fun undo(){control.post{if(mutableState.value.busy)return@post;finishRecording();history.undo(document())?.let{adopt(it);syncEngine()}}}
    fun redo(){control.post{if(mutableState.value.busy)return@post;finishRecording();history.redo(document())?.let{adopt(it);syncEngine()}}}
    fun overdub(value: Boolean){control.post{mutableState.value=mutableState.value.copy(overdub=value)}}
    fun record(slot: Int){control.post{
        if(mutableState.value.recording>=0){finishRecording();return@post}
        if(!wantsAudio||mutableState.value.busy)return@post
        val c=mutableState.value.project.clip(slot)
        if(c?.isAudio==true){message("Choose a MIDI clip to record keyboard or USB notes.");return@post}
        history.begin(document())
        commit{d->val clip=c?:Clip(slot,"Recorded MIDI",d.project.barBeats*4)
            d.copy(project=d.project.copy(mode=0).put(if(mutableState.value.overdub)clip else clip.copy(notes=emptyList())),tracks=d.tracks.toMutableList().also{it[slot%16]=it[slot%16].copy(armed=true)})}
        mutableState.value=mutableState.value.copy(recording=slot)
        pendingLaunch=Triple(slot%16,slot/16,false);message("MIDI recording starts at the launch boundary. Play the keyboard or USB controller.")
    }}
    private fun recordEvent(track: Int,key: Int,velocity: Float,id: Int,on: Boolean){
        val address=(track.toLong() shl 32)+(id.toLong() and 0xffffffffL)
        if(!on){recorded.remove(address)?.let{finishNote(it)};return}
        val slot=mutableState.value.recording;if(slot<0||slot%16!=track||!mutableState.value.tracks[track].armed)return
        val seq=bridge.sequence();val c=mutableState.value.project.clip(slot)?:return
        if(seq.active[track]!=slot)return
        recorded[address]=Recorded(track,key,velocity,bridge.stats().beat,seq.phase[track]*c.length)
    }
    private fun finishNote(note: Recorded){
        val slot=mutableState.value.recording;if(slot<0)return
        val c=mutableState.value.project.clip(slot)?:return
        val duration=(bridge.stats().beat-note.absolute).coerceIn(1.0/960,c.length)
        commit { d->var clip=d.project.clip(slot)?:return@commit d
            val start=note.local.coerceIn(0.0,clip.length-1.0/960);val first=min(duration,clip.length-start)
            if(clip.notes.size<256&&first>=1.0/960)clip=clip.add(start,note.pitch,first,note.velocity)
            if(duration-first>=1.0/960&&clip.notes.size<256)clip=clip.add(0.0,note.pitch,duration-first,note.velocity)
            d.copy(project=d.project.put(clip))
        }
    }
    private fun finishRecording(){if(mutableState.value.recording<0)return;recorded.values.toList().forEach{finishNote(it)};recorded.clear();history.end();mutableState.value=mutableState.value.copy(recording=-1,canUndo=history.canUndo,canRedo=history.canRedo)}
    private fun loadAssets(d: ProjectDocument){
        check(bridge.loadAssets(d.project.assets.map{it.id to files.audioFile(it)})){"Audio is missing, unsupported, or exceeds the 512 MiB decoded audio budget."}
        for(a in d.project.assets){val info=requireNotNull(bridge.assetInfo(a.id));require(info[0].toInt()==a.rate&&info[1].toLong()==a.frames){"Audio file does not match its project metadata."}}
    }
    private fun work(title: String,task: (ProjectDocument)->ProjectDocument?,done: String,resetHistory: Boolean=false){
        control.post {
            if(!mutableState.value.ready||mutableState.value.busy)return@post
            finishRecording();val snapshot=document();cancelled.set(false)
            mutableState.value=mutableState.value.copy(busy=true,work=title,progress=0f)
            ContextCompat.startForegroundService(this,Intent(this,AudioService::class.java).setAction(ACTION_WORK))
            io.execute {
                var result: ProjectDocument?=null;var error: String?=null
                try{result=task(snapshot);check(!cancelled.get()){ "Cancelled." }}catch(e: Exception){error=e.message?:"File operation failed.";runCatching{loadAssets(snapshot)}}
                control.post {if(!destroyed){
                    mutableState.value=mutableState.value.copy(busy=false,work="",progress=0f)
                    if(error==null&&result!=null){if(resetHistory)history.clear()else history.commit(document(),result);adopt(result);sent=null;syncEngine()}
                    message(error?:done)
                    if(wantsAudio)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    else {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
                }}
            }
        }
    }
    fun cancelWork(){cancelled.set(true);if(::bridge.isInitialized)bridge.cancelExport()}
    fun importAudio(uri: Uri,slot: Int){work("Importing audio",{d->
        val target=if(d.project.clip(slot)==null)slot else d.project.emptySlot(slot%16)?:error("This track has no empty clip slots.")
        val id=(0..63).firstOrNull{i->d.project.asset(i)==null}?:error("The project holds up to 64 audio files.")
        val (name,file)=files.importAudio(uri,cancelled)
        try{
            check(bridge.loadAssets(d.project.assets.map{it.id to files.audioFile(it)}+(id to file))){"Unsupported WAV or decoded audio budget exceeded (512 MiB)."}
            val info=requireNotNull(bridge.assetInfo(id));val asset=AudioAsset(id,name,file.name,info[0].toInt(),info[1].toLong(),info.drop(2).map{it.toFloat()})
            val length=asset.frames.toDouble()/asset.rate*d.bpm/60
            val project=d.project.copy(assets=d.project.assets+asset).put(Clip(target,name.take(64).ifBlank{"Audio"},length.coerceAtLeast(.0625),asset=id,last=asset.frames))
            d.copy(project=project).also{require(it.valid()){ "Audio exceeds the supported timeline or memory limits." }}
        }catch(e: Exception){file.delete();throw e}
    },"Audio imported into the selected track. Select its clip to edit the waveform.")}
    fun saveProject(uri: Uri){work("Saving project",{files.saveArchive(it,uri,cancelled);null},"Project saved with synths, effects and audio.")}
    fun openProject(uri: Uri){control.post{if(!mutableState.value.busy)stopAudio("Opening project…")};work("Opening project",{val d=files.openArchive(uri,cancelled);loadAssets(d);d},"Project opened. Start audio to play.",true)}
    fun starter(){control.post{if(!mutableState.value.ready||mutableState.value.busy)return@post;stopTransport();commit{old->StarterProject.create(presets).let{it.copy(project=it.project.copy(assets=old.project.assets))}};message("Green circuit loaded. Start audio and press Play, or launch individual clips.")}}
    fun newProject(){control.post{if(mutableState.value.busy)return@post;stopTransport();history.clear();adopt(document().copy(project=Project(),tracks=List(16){TrackControls()}));syncEngine();message("New project. Your synth patches and effects are retained.")}}
    fun exportWave(uri: Uri,rate: Int=48000,tail: Float=4f){work("Rendering WAV",{d->
        require(d.project.regions.isNotEmpty()){ "Place clips in Arrangement before exporting." }
        val file=File(cacheDir,"export-${System.nanoTime()}.wav")
        try{val result=bridge.export(file,d,rate,tail);check(result==0){when(result){2->"Could not write the WAV file.";3->"Cancelled.";else->"Could not render this arrangement."}}
            files.publishWave(file,uri,cancelled)
        }finally{file.delete()};null
    },"24-bit stereo WAV exported.")}

    override fun onDestroy() {
        destroyed=true;unregisterReceiver(noisyReceiver);control.removeCallbacksAndMessages(null)
        control.post {
            finishRecording();midi?.close();cancelled.set(true)
            if(::bridge.isInitialized){bridge.cancelExport();bridge.stop()}
            audioManager.abandonAudioFocusRequest(focusRequest)
            val d=document()
            io.execute {runCatching{files.save(d)};control.post{if(::bridge.isInitialized)bridge.close();thread.quitSafely()}}
            io.shutdown()
        }
        super.onDestroy()
    }
    companion object {
        const val ACTION_START="com.weskestis.astrastudio.START"
        const val ACTION_STOP="com.weskestis.astrastudio.STOP"
        private const val ACTION_WORK="com.weskestis.astrastudio.WORK"
        private const val CHANNEL="astra_audio"
        private const val NOTIFICATION=41
    }
}
