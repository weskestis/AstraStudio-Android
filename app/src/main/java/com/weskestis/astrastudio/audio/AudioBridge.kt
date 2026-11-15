package com.weskestis.astrastudio.audio

import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.synth.SynthSchema
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.fx.FxSchema
import com.weskestis.astrastudio.fx.FxStats
import com.weskestis.astrastudio.project.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioStats(
    val running: Boolean = false,
    val sampleRate: Int = 0,
    val framesPerBurst: Int = 0,
    val bufferFrames: Int = 0,
    val cpuLoad: Double = 0.0,
    val xruns: Int = -1,
    val frame: Long = 0,
    val activeVoices: Int = 0,
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    val droppedCommands: Long = 0,
    val lateCommands: Long = 0,
    val clippedSamples: Long = 0,
    val error: Int = 0,
    val exclusive: Boolean = false,
    val bpm: Float = 145f,
    val playing: Boolean = false,
    val beat: Double = 0.0,
) {
    // Software output buffer duration, NOT measured round-trip latency.
    val bufferMs: Double get() = if (sampleRate > 0) bufferFrames * 1000.0 / sampleRate else 0.0
}

/** Owned by AudioService. Never create engines inside a Composable.
 * The synchronized control boundary serializes UI/MIDI producers for the SPSC
 * queue and excludes close from in-flight calls. Audio callbacks stay in C++.
 */
class AudioBridge : AutoCloseable {
    private var handle = nativeCreate().also { check(it != 0L) { "Unable to allocate audio engine" } }
    private val statsBuffer = ByteBuffer.allocateDirect(20 * Double.SIZE_BYTES).order(ByteOrder.nativeOrder())

    private val fxBuffer = ByteBuffer.allocateDirect(133 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    private val sequenceBuffer = ByteBuffer.allocateDirect(49 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    @Synchronized fun start(bufferBursts: Int = 3): Int {
        require(bufferBursts in 2..8)
        return if (handle != 0L) nativeStart(handle, bufferBursts) else -10000
    }
    @Synchronized fun stop() { if (handle != 0L) nativeStop(handle) }
    @Synchronized fun panic() { if (handle != 0L) nativePanic(handle) }
    @Synchronized fun setBufferBursts(bursts: Int): Int {
        require(bursts in 2..8)
        return if (handle != 0L) nativeSetBuffer(handle, bursts) else -10000
    }
    @Synchronized fun noteOn(track: Int, note: Int, velocity: Float, noteId: Int, frame: Long = IMMEDIATE): Boolean =
        send(0, track, note, noteId, velocity, frame)
    @Synchronized fun noteOff(track: Int, noteId: Int, frame: Long = IMMEDIATE): Boolean =
        send(1, track, 0, noteId, 0f, frame)
    @Synchronized fun masterGain(value: Float): Boolean = send(2, value = value)
    @Synchronized fun trackGain(track: Int, value: Float): Boolean = send(3, track, value = value)
    @Synchronized fun trackPan(track: Int, value: Float): Boolean = send(4, track, value = value)
    @Synchronized fun trackMute(track: Int, mute: Boolean): Boolean = send(5, track, value = if (mute) 1f else 0f)
    @Synchronized fun trackSolo(track: Int, solo: Boolean): Boolean = send(6, track, value = if (solo) 1f else 0f)
    @Synchronized fun tempo(bpm: Float): Boolean = send(7, value = bpm)
    @Synchronized fun transport(playing: Boolean): Boolean = send(8, value = if (playing) 1f else 0f)
    @Synchronized fun metronome(enabled: Boolean): Boolean = send(9, value = if (enabled) 1f else 0f)

    @Synchronized fun preset(index: Int): SynthPatch {
        require(index in SynthSchema.presets.indices)
        return SynthPatch(SynthSchema.presets[index], checkNotNull(nativePreset(index)).toList())
    }
    @Synchronized fun patch(track: Int, patch: SynthPatch): Boolean =
        handle != 0L && nativeSetPatch(handle, track, patch.wire.toFloatArray())

    @Synchronized fun fxPreset(index: Int): FxConfig {
        require(index in FxSchema.presets.indices)
        return FxConfig(FxSchema.presets[index], checkNotNull(nativeFxPreset(index)).toList())
    }
    @Synchronized fun effects(track: Int, config: FxConfig): Boolean =
        handle != 0L && nativeSetEffects(handle, track, config.wire.toFloatArray())
    @Synchronized fun limiterDrive(db: Float): Boolean = send(12, value = db)
    @Synchronized fun limiterRelease(ms: Float): Boolean = send(13, value = ms)
    @Synchronized fun meterTrack(track: Int): Boolean = send(14, value = track.toFloat())
    @Synchronized fun fxStats(): FxStats {
        if (handle == 0L || !nativeReadFxStats(handle, fxBuffer)) return FxStats()
        fun v(i: Int) = fxBuffer.getFloat(i * Float.SIZE_BYTES)
        return FxStats(List(16){v(it)}, List(48){v(16+it)}, v(64), v(65).toInt(), List(64){v(66+it)}, v(130), v(131).toInt(), v(132).toLong())
    }

    @Synchronized fun project(project: Project): Boolean = handle != 0L && nativeSetProject(handle, project.nativeWire())
    @Synchronized fun launch(track: Int, scene: Int, quantum: Float) = send(16, track, scene, value = quantum)
    @Synchronized fun scene(scene: Int, quantum: Float) = send(17, note = scene, value = quantum)
    @Synchronized fun seek(beat: Double) = send(18, value = beat.toFloat())
    @Synchronized fun sendA(track: Int, amount: Float) = send(19, track, value = amount)
    @Synchronized fun sendB(track: Int, amount: Float) = send(20, track, value = amount)
    @Synchronized fun preview(asset: Int)=send(21,note=asset,value=.65f)
    @Synchronized fun stopPreview()=send(22,value=0f)
    @Synchronized fun sequence(): SequenceStats {
        if(handle==0L||!nativeReadSequence(handle,sequenceBuffer))return SequenceStats()
        fun v(i: Int)=sequenceBuffer.getFloat(i*4)
        return SequenceStats(List(16){v(it).toInt()},List(16){v(16+it).toInt()},List(16){v(32+it)},v(48).toInt())
    }
    // These worker calls hold the Kotlin monitor only while copying the opaque
    // handle. Native code snapshots shared sample ownership before long I/O/DSP.
    fun loadAssets(assets: List<Pair<Int,File>>): Boolean {
        val h=synchronized(this){handle}
        return h!=0L&&nativeLoadAssets(h,assets.map{it.first}.toIntArray(),assets.map{it.second.absolutePath}.toTypedArray())
    }
    fun assetInfo(id: Int): DoubleArray? {val h=synchronized(this){handle};return if(h==0L)null else nativeAssetInfo(h,id)}
    fun export(file: File,document: ProjectDocument,rate: Int,tail: Float,floating: Boolean=false): Int {
        val h=synchronized(this){handle}
        return nativeExport(h,file.absolutePath,document.project.nativeWire(),document.patches.flatMap{it.wire}.toFloatArray(),document.effects.flatMap{it.wire}.toFloatArray(),document.mixWire(),rate,tail,floating)
    }
    fun stretch(file: File,asset: Int,first: Long,last: Long,frames: Long,semitones: Double): Int {
        val h=synchronized(this){handle};return if(h==0L)1 else nativeStretch(h,file.absolutePath,asset,first,last,frames,semitones)
    }
    private external fun nativeStretch(handle: Long,path: String,asset: Int,first: Long,last: Long,frames: Long,semitones: Double): Int
    fun cancelExport()=nativeCancelExport()
    fun exportProgress()=nativeExportProgress()
    private external fun nativeSetProject(handle: Long, data: DoubleArray): Boolean
    private external fun nativeReadSequence(handle: Long, buffer: ByteBuffer): Boolean
    private external fun nativeLoadAssets(handle: Long, ids: IntArray, paths: Array<String>): Boolean
    private external fun nativeAssetInfo(handle: Long, id: Int): DoubleArray?
    private external fun nativeExport(handle: Long, path: String, data: DoubleArray, patches: FloatArray, effects: FloatArray, mix: FloatArray, rate: Int, tail: Float, floating: Boolean): Int
    private external fun nativeCancelExport()
    private external fun nativeExportProgress(): Float

    private fun send(type: Int, track: Int = 0, note: Int = 0, noteId: Int = 0, value: Float, frame: Long = IMMEDIATE): Boolean {
        if (handle == 0L || !value.isFinite() || frame < -1) return false
        return nativeSend(handle, type, track, note, noteId, value, frame)
    }

    @Synchronized fun stats(): AudioStats {
        if (handle == 0L || !nativeReadStats(handle, statsBuffer)) return AudioStats()
        fun v(index: Int) = statsBuffer.getDouble(index * Double.SIZE_BYTES)
        return AudioStats(v(0) != 0.0, v(1).toInt(), v(2).toInt(), v(3).toInt(), v(4), v(5).toInt(),
            v(6).toLong(), v(7).toInt(), v(8).toFloat(), v(9).toFloat(), v(10).toLong(), v(11).toLong(),
            v(12).toLong(), v(13).toInt(), v(14) != 0.0, v(15).toFloat(), v(16) != 0.0, v(17))
    }

    @Synchronized override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }
    private external fun nativeFxPreset(index: Int): FloatArray?
    private external fun nativeSetEffects(handle: Long, track: Int, data: FloatArray): Boolean
    private external fun nativeReadFxStats(handle: Long, buffer: ByteBuffer): Boolean
    private external fun nativePreset(index: Int): FloatArray?
    private external fun nativeSetPatch(handle: Long, track: Int, values: FloatArray): Boolean
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, bursts: Int): Int
    private external fun nativeStop(handle: Long)
    private external fun nativePanic(handle: Long)
    private external fun nativeSetBuffer(handle: Long, bursts: Int): Int
    private external fun nativeSend(handle: Long, type: Int, track: Int, note: Int, noteId: Int, value: Float, frame: Long): Boolean
    private external fun nativeReadStats(handle: Long, destination: ByteBuffer): Boolean

    companion object {
        const val IMMEDIATE = -1L
        const val TRACK_COUNT = 16
        init { System.loadLibrary("astra_audio") }
    }
}
