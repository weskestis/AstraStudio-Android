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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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

data class TrackControls(val gain: Float = 0.8f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false)
data class StudioState(
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
    private val mutableState = MutableStateFlow(StudioState())
    val state = mutableState.asStateFlow()
    private lateinit var thread: HandlerThread
    private lateinit var control: Handler
    private lateinit var bridge: AudioBridge
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var midi: MidiInputManager? = null
    private var presets = emptyList<SynthPatch>()
    private var fxPresets = emptyList<FxConfig>()
    private val saveEffects = Runnable {
        getSharedPreferences("fx-v1", MODE_PRIVATE).edit {
            state.value.effects.forEachIndexed { i, config -> putString("track-$i", config.encode()) }
            putFloat("limiter-drive", state.value.limiterDrive)
            putFloat("limiter-release", state.value.limiterRelease)
        }
    }
    private val savePatches = Runnable {
        getSharedPreferences("synth-v1", MODE_PRIVATE).edit {
            state.value.patches.forEachIndexed { i, patch -> putString("track-$i", patch.encode()) }
        }
    }
    private var wantsAudio = false
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
            mutableState.value = state.value.copy(patches = List(AudioBridge.TRACK_COUNT) { index ->
                SynthPatch.decode(saved.getString("track-$index", null)) ?: presets[when (index) { 0 -> 1; 1 -> 3; 2 -> 5; else -> 0 }]
            })
            fxPresets = FxSchema.presets.indices.map(bridge::fxPreset)
            val savedFx = getSharedPreferences("fx-v1", MODE_PRIVATE)
            mutableState.value = state.value.copy(
                effects = List(AudioBridge.TRACK_COUNT) { index -> FxConfig.decode(savedFx.getString("track-$index", null)) ?: fxPresets[0] },
                limiterDrive = savedFx.getFloat("limiter-drive", 0f).takeIf { it.isFinite() }?.coerceIn(0f,24f) ?: 0f,
                limiterRelease = savedFx.getFloat("limiter-release", 80f).takeIf { it.isFinite() }?.coerceIn(20f,500f) ?: 80f,
            )
            control.post(poll)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                control.post { startAudio() }
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
            .setContentText("Audio engine active • tap to return")
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
        val result = bridge.start(state.value.bufferBursts)
        if (result != 0) { stopAudio("Could not open audio: $result"); return }
        applyMix()
        if (midi == null) {
            midi = MidiInputManager(this, control, bridge) { count ->
                mutableState.value = state.value.copy(midiDevices = count)
            }.also { it.start() }
        }
        mutableState.value = state.value.copy(message = "Engine ready. Hold a pad or play the timing demo.")
    }

    private fun applyMix() {
        val s = state.value
        bridge.masterGain(s.master)
        bridge.tempo(s.bpm)
        bridge.metronome(s.metronome)
        bridge.limiterDrive(s.limiterDrive)
        bridge.limiterRelease(s.limiterRelease)
        bridge.meterTrack(s.meterTrack)
        s.effects.forEachIndexed { index, config -> bridge.effects(index, config) }
        s.patches.forEachIndexed { index, patch -> bridge.patch(index, patch) }
        s.tracks.forEachIndexed { index, track ->
            bridge.trackGain(index, track.gain)
            bridge.trackPan(index, track.pan)
            bridge.trackMute(index, track.mute)
            bridge.trackSolo(index, track.solo)
        }
    }

    private fun stopAudio(message: String) {
        wantsAudio = false
        midi?.close()
        midi = null
        bridge.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
        mutableState.value = state.value.copy(stats = AudioStats(), fxStats = FxStats(), message = message, midiDevices = 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val poll = object : Runnable {
        override fun run() {
            val stats = bridge.stats()
            mutableState.value = state.value.copy(stats = stats, fxStats = if (stats.running) bridge.fxStats() else FxStats())
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
                        bridge.stop()
                        val result = bridge.start(state.value.bufferBursts)
                        if (result == 0) {
                            applyMix()
                            mutableState.value = state.value.copy(message = "Audio route reopened. Playback stopped; ready to play.")
                        } else mutableState.value = state.value.copy(message = "Reopening audio route: attempt $reconnectAttempts / 3")
                    }
                }
            }
            control.postDelayed(this, 50)
        }
    }

    fun stopFromUi() { control.post { stopAudio("Audio stopped.") } }
    fun panic() { control.post { bridge.panic(); mutableState.value = state.value.copy(message = "All notes and scheduled events cleared.") } }
    fun audition(track: Int, enabled: Boolean) { control.post {
        if (enabled && wantsAudio) bridge.noteOn(track, 29, 0.8f, 20000 + track) else bridge.noteOff(track, 20000 + track)
    } }
    fun noteOn(track: Int, note: Int, id: Int) { control.post { bridge.noteOn(track, note, 0.85f, id) } }
    fun noteOff(track: Int, id: Int) { control.post { bridge.noteOff(track, id) } }
    fun releaseTouchNotes() { control.post { for (t in 0 until AudioBridge.TRACK_COUNT) {
        for (key in 0..7) bridge.noteOff(t, 1 + t * 8 + key)
        bridge.noteOff(t, 20000 + t)
    } } }
    fun transport() { control.post { bridge.transport(!bridge.stats().playing) } }
    fun master(value: Float) { control.post {
        mutableState.value = state.value.copy(master = value.coerceIn(0f, 1f))
        bridge.masterGain(state.value.master)
    } }
    fun tempo(value: Float) { control.post { setTempo(value) } }
    private fun setTempo(value: Float) {
        mutableState.value = state.value.copy(bpm = value.coerceIn(30f, 300f))
        bridge.tempo(state.value.bpm)
    }
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
        mutableState.value = state.value.copy(metronome = value)
        bridge.metronome(value)
    } }
    fun buffer(bursts: Int) { control.post {
        if (bursts in 2..8) {
            mutableState.value = state.value.copy(bufferBursts = bursts)
            val result = bridge.setBufferBursts(bursts)
            if (wantsAudio && result < 0) mutableState.value = state.value.copy(message = "This output route did not accept a buffer change ($result).")
        }
    } }
    fun track(index: Int, value: TrackControls) { control.post {
        if (index !in state.value.tracks.indices) return@post
        val safe = value.copy(gain = value.gain.coerceIn(0f, 2f), pan = value.pan.coerceIn(-1f, 1f))
        mutableState.value = state.value.copy(tracks = state.value.tracks.toMutableList().also { it[index] = safe })
        bridge.trackGain(index, safe.gain)
        bridge.trackPan(index, safe.pan)
        bridge.trackMute(index, safe.mute)
        bridge.trackSolo(index, safe.solo)
    } }

    private fun editPatch(track: Int, transform: (SynthPatch) -> SynthPatch) {
        if (track !in state.value.patches.indices) return
        val patch = transform(state.value.patches[track])
        if (wantsAudio && !bridge.patch(track, patch)) {
            mutableState.value = state.value.copy(message = "Synth update busy. Try the control again.")
            return
        }
        mutableState.value = state.value.copy(patches = state.value.patches.toMutableList().also { it[track] = patch })
        control.removeCallbacks(savePatches)
        control.postDelayed(savePatches, 500)
    }
    fun preset(track: Int, index: Int) { control.post {
        if (index in presets.indices) editPatch(track) { presets[index] }
    } }
    fun synthParameter(track: Int, id: Int, value: Float) { control.post { editPatch(track) { it.parameter(id, value) } } }
    fun synthRoute(track: Int, slot: Int, field: Int, value: Float) { control.post { editPatch(track) { it.route(slot, field, value) } } }
    fun synthShape(track: Int, lfo: Int, kind: Int) { control.post { editPatch(track) { it.shape(lfo, kind) } } }
    fun paintLfo(track: Int, lfo: Int, from: Int, fromValue: Float, to: Int, toValue: Float) {
        control.post { editPatch(track) { it.paint(lfo, from, fromValue, to, toValue) } }
    }

    private fun editEffects(track: Int, transform: (FxConfig) -> FxConfig) {
        if (track !in state.value.effects.indices) return
        val config = transform(state.value.effects[track])
        if (wantsAudio && !bridge.effects(track, config)) {
            mutableState.value = state.value.copy(message = "Effects update busy. Try the control again.")
            return
        }
        mutableState.value = state.value.copy(effects = state.value.effects.toMutableList().also { it[track] = config })
        control.removeCallbacks(saveEffects); control.postDelayed(saveEffects, 500)
    }
    fun fxPreset(track: Int, index: Int) { control.post { if (index in fxPresets.indices) editEffects(track) { fxPresets[index] } } }
    fun fxParameter(track: Int, id: Int, value: Float) { control.post { editEffects(track) { it.parameter(id, value) } } }
    fun fxMove(track: Int, unit: Int, direction: Int) { control.post { editEffects(track) { it.move(unit, direction) } } }
    fun fxLane(track: Int, unit: Int, lane: Int) { control.post { editEffects(track) { it.lane(unit, lane) } } }
    fun fxMacro(track: Int, macro: Int, field: Int, value: Float) { control.post { editEffects(track) { it.macro(macro, field, value) } } }
    fun meterTrack(track: Int) { control.post {
        if (track in 0..16) { mutableState.value = state.value.copy(meterTrack = track); bridge.meterTrack(track) }
    } }
    fun limiterDrive(value: Float) { control.post {
        if (!value.isFinite()) return@post
        val safe = value.coerceIn(0f,24f); if (wantsAudio && !bridge.limiterDrive(safe)) return@post
        mutableState.value = state.value.copy(limiterDrive = safe)
        control.removeCallbacks(saveEffects); control.postDelayed(saveEffects,500)
    } }
    fun limiterRelease(value: Float) { control.post {
        if (!value.isFinite()) return@post
        val safe = value.coerceIn(20f,500f); if (wantsAudio && !bridge.limiterRelease(safe)) return@post
        mutableState.value = state.value.copy(limiterRelease = safe)
        control.removeCallbacks(saveEffects); control.postDelayed(saveEffects,500)
    } }

    /** Two sample-scheduled bars using the selected track's current synth. */
    fun demo(track: Int) { control.post {
        if (track !in state.value.tracks.indices) return@post
        val stats = bridge.stats()
        if (!stats.running) return@post
        bridge.transport(false)
        bridge.transport(true)
        val base = stats.frame + stats.sampleRate / 5
        val step = stats.sampleRate * 60.0 / state.value.bpm / 2.0
        val notes = intArrayOf(29, 29, 36, 29, 32, 29, 39, 36, 29, 41, 36, 32, 29, 36, 39, 29)
        notes.forEachIndexed { i, note ->
            val id = nextDemoId++
            val at = base + (step * i).toLong()
            bridge.noteOn(track, note, 0.9f, id, at)
            bridge.noteOff(track, id, at + (step * 0.7).toLong())
        }
        mutableState.value = state.value.copy(message = "Two-bar F-minor phrase • Track ${track + 1} • ${state.value.patches[track].name}")
    } }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        control.removeCallbacksAndMessages(null)
        control.post {
            savePatches.run()
            saveEffects.run()
            midi?.close()
            if (::bridge.isInitialized) bridge.close()
            audioManager.abandonAudioFocusRequest(focusRequest)
            thread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.weskestis.astrastudio.START"
        const val ACTION_STOP = "com.weskestis.astrastudio.STOP"
        private const val CHANNEL = "astra_audio"
        private const val NOTIFICATION = 41
    }
}
