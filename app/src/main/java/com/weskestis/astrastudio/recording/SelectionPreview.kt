package com.weskestis.astrastudio.recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Disk-backed audition at source rate; no source audio is modified. */
class SelectionPreview : AutoCloseable {
    private val worker=Executors.newSingleThreadExecutor()
    private val generation=AtomicLong()
    @Volatile private var track: AudioTrack?=null
    fun stop(){generation.incrementAndGet();runCatching{track?.pause()};runCatching{track?.flush()}}
    fun play(file: File,first: Long,last: Long,finished: (String)->Unit){
        stop();val job=generation.get()
        worker.execute {
            var error=""
            try{if(job!=generation.get())return@execute
                WaveFile(file).use{wave->require(first>=0&&last in (first+1)..wave.frames)
                    val minimum=AudioTrack.getMinBufferSize(wave.rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);require(minimum>0){"This sample rate cannot be auditioned on this output."}
                    val output=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(wave.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(maxOf(minimum*2,8192*8)).setTransferMode(AudioTrack.MODE_STREAM).build()
                    track=output;output.setVolume(.65f)
                    if(job!=generation.get())return@use
                    output.play();var position=first;var written=0L;val data=FloatArray(8192*2)
                    var lastProgress=System.nanoTime()
                    while(position<last&&job==generation.get()){
                        val n=wave.read(position,minOf(8192L,last-position).toInt(),data);var offset=0
                        while(offset<n*2&&job==generation.get()){
                            val count=output.write(data,offset,n*2-offset,AudioTrack.WRITE_NON_BLOCKING);check(count>=0){"Audio output disconnected."}
                            if(count==0){check(System.nanoTime()-lastProgress<5_000_000_000L){"Audio output stopped responding."};Thread.sleep(5)}else {offset+=count;written+=count/2;lastProgress=System.nanoTime()}
                        };position+=n
                    }
                    val drainStart=System.nanoTime()
                    while(job==generation.get()&&(output.playbackHeadPosition.toLong() and 0xffffffffL)<written){check(System.nanoTime()-drainStart<5_000_000_000L){"Audio output stopped responding."};Thread.sleep(10)}
                }
            }catch(e: Exception){error=e.message?:"Could not audition selection."}
            finally{val old=track;track=null;runCatching{old?.stop()};old?.release();if(job==generation.get())finished(error)}
        }
    }
    override fun close(){stop();worker.shutdown()}
}
