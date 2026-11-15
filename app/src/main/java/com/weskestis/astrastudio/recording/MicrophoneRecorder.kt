package com.weskestis.astrastudio.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class RecordedTake(val path: String,val name: String,val rate: Int,val channels: Int,val frames: Long)
data class CaptureState(val active: Boolean=false,val stopping: Boolean=false,val frames: Long=0,val rate: Int=48000,
    val channels: Int=1,val peak: Float=0f,val rms: Float=0f,val clipped: Long=0,val format: String="",
    val route: String="",val status: String="Ready to record",val takes: List<RecordedTake> = emptyList(),val latest: String="")

/** Blocking capture + disk writing have their own worker; playback's real-time
 * callback never sees Android recorder calls, file I/O, or a growing audio array.
 */
class MicrophoneRecorder(private val context: Context,private val completed: ()->Unit) : AutoCloseable {
    val directory=File(context.filesDir,"recordings").also{it.mkdirs()}
    private val mutable=MutableStateFlow(CaptureState())
    val state=mutable.asStateFlow()
    private var snapshot: CaptureState
        get()=mutable.value
        set(value){mutable.value=value}
    @Synchronized private fun update(edit: (CaptureState)->CaptureState){snapshot=edit(snapshot)}
    private val worker=Executors.newSingleThreadExecutor()
    private val active=AtomicBoolean(false)
    private val stop=AtomicBoolean(false)
    @Volatile private var input: AudioRecord?=null
    @Volatile private var closed=false
    init{worker.execute{recover();refresh()}}
    private fun recover(){directory.listFiles()?.filter{it.extension=="part"}?.forEach{file->if(FloatWaveWriter.recover(file))file.renameTo(File(directory,file.nameWithoutExtension+".wav"))else if(file.length()<=44)file.delete()}}
    fun refresh(latest: String=""){
        val takes=directory.listFiles()?.filter{it.extension=="wav"}?.sortedByDescending{it.lastModified()}?.mapNotNull { file->
            runCatching{WaveFile(file).use{RecordedTake(file.absolutePath,displayName(file),it.rate,it.channels,it.frames)}}.getOrNull()
        }?:emptyList()
        update{it.copy(takes=takes,latest=if(latest.isBlank())it.latest else latest)}
    }
    fun start(stereo: Boolean): Boolean {
        if(closed||!active.compareAndSet(false,true))return false
        stop.set(false);update{it.copy(active=true,stopping=false,frames=0,peak=0f,rms=0f,clipped=0,format="",route="",status="Opening microphone…")}
        worker.execute{capture(stereo)};return true
    }
    fun stop(){if(active.get()){stop.set(true);update{if(it.active)it.copy(stopping=true,status="Saving recording…")else it};runCatching{input?.stop()}}}
    fun discard(path: String){if(!active.get())worker.execute{
        val file=File(path);if(file.parentFile?.canonicalFile==directory.canonicalFile&&file.extension=="wav"){file.delete();refresh();update{it.copy(latest="",status="Recording discarded.")}}
    }}
    private fun capture(stereo: Boolean){
        val stamp=java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.US).format(java.util.Date())
        val file=File(directory,"Take_${stamp}_${UUID.randomUUID().toString().take(8)}.part")
        var warning="";var saved=""
        try{
            check(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Allow microphone access to record."}
            check(directory.usableSpace>32L*1024*1024){"Free at least 32 MiB of storage to record."}
            val manager=context.getSystemService(AudioManager::class.java)
            val raw=manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)=="true"
            var record: AudioRecord?=null;var label=""
            search@for(source in listOf(if(raw)MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC).distinct()){
                for(channels in (if(stereo)listOf(2,1)else listOf(1)))for(rate in listOf(48000,44100))for(encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT,AudioFormat.ENCODING_PCM_16BIT)){
                    val mask=if(channels==2)AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                    val min=AudioRecord.getMinBufferSize(rate,mask,encoding);if(min<=0)continue
                    val candidate=runCatching{AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(encoding).build())
                        .setBufferSizeInBytes(max(min*4,rate*channels*4/2)).build()}.getOrNull()?:continue
                    if(candidate.state!=AudioRecord.STATE_INITIALIZED){candidate.release();continue}
                    record=candidate;label=when(source){MediaRecorder.AudioSource.UNPROCESSED->"Unprocessed input";MediaRecorder.AudioSource.VOICE_RECOGNITION->"Voice recognition input";else->"Microphone input (device processing may apply)"};break@search
                }
            }
            val recorder=checkNotNull(record){"This audio input could not be opened. Close another recorder and retry."};input=recorder
            val rate=recorder.sampleRate;val channels=recorder.channelCount;val floating=recorder.audioFormat==AudioFormat.ENCODING_PCM_FLOAT
            val data=FloatArray(4096*channels);val pcm=ShortArray(data.size);var clipped=0L;var nextUpdate=0L;var checkpoint=SystemClock.elapsedRealtime();var lastRead=checkpoint
            update{it.copy(rate=rate,channels=channels,format="$label · ${if(floating)"float input" else "16-bit input"} → float WAV")}
            if(stop.get())return
            recorder.startRecording();check(recorder.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Microphone did not start."}
            FloatWaveWriter(file,rate,channels).use{writer->
                while(!stop.get()){
                    val n=if(floating)recorder.read(data,0,data.size,AudioRecord.READ_BLOCKING)else recorder.read(pcm,0,pcm.size,AudioRecord.READ_BLOCKING).also{count->if(count>0)repeat(count){data[it]=pcm[it]/32768f}}
                    if(stop.get())break
                    check(n>=0){"Audio input disconnected (code $n). The captured part has been saved."}
                    if(n==0){check(SystemClock.elapsedRealtime()-lastRead<5000){"Audio input stopped responding."};continue};lastRead=SystemClock.elapsedRealtime()
                    require(n%channels==0);var peak=0f;var sum=0.0
                    repeat(n){i->val v=data[i];require(v.isFinite());peak=max(peak,abs(v));sum+=v.toDouble()*v;if(abs(v)>=.999f)clipped++}
                    val keep=min(n.toLong(),(MAX_TAKE_BYTES-writer.samples*4)/4).toInt()/channels*channels
                    writer.write(data,keep)
                    if(lastRead>=nextUpdate){nextUpdate=lastRead+100
                        val silenced=recorder.activeRecordingConfiguration?.isClientSilenced==true
                        val route=recorder.routedDevice?.productName?.toString()?:"Default audio input"
                        update{it.copy(frames=writer.samples/channels,peak=peak,rms=sqrt(sum/n).toFloat(),clipped=clipped,route=route,status=if(silenced)"Android has silenced this microphone. Check microphone privacy and other apps." else "Recording · Back keeps this take running")}
                    }
                    if(lastRead-checkpoint>=1000){writer.checkpoint();checkpoint=lastRead
                        if(directory.usableSpace<16L*1024*1024){warning="Stopped before storage filled.";break}}
                    if(writer.samples*4>=MAX_TAKE_BYTES){warning="Reached the 1 GiB take limit.";break}
                }
                update{it.copy(frames=writer.samples/channels)}
            }
        }catch(e: Exception){warning=e.message?:"Recording failed."}
        finally{
            val old=input;input=null;runCatching{old?.stop()};old?.release()
            if(file.exists()&&FloatWaveWriter.recover(file)){val target=File(directory,file.nameWithoutExtension+".wav");if(file.renameTo(target))saved=target.absolutePath else warning="Could not finalize the recording; it will be recovered on next launch."}
            else if(file.length()>44)warning="Could not finalize the recording. Partial audio is retained for recovery on next launch. $warning"
            else file.delete()
            active.set(false);update{it.copy(active=false,stopping=false,peak=0f,rms=0f,status=if(saved.isNotEmpty())"Take saved. $warning".trim()else warning.ifBlank{"No audio captured."})}
            refresh(saved);completed()
        }
    }
    override fun close(){closed=true;stop();worker.shutdown()}
    companion object{
        const val MAX_TAKE_BYTES=1024L*1024*1024
        fun displayName(file: File)=file.nameWithoutExtension.replace(Regex("_(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{8})$"),"").removePrefix("Import_").replace('_',' ')
    }
}
