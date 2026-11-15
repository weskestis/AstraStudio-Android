package com.weskestis.astrastudio.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class RecordingTests {
    private fun file()=File.createTempFile("astra-record-",".wav").also{it.deleteOnExit()}
    private fun tone(hz: Double,rate: Int=48000,seconds: Double=1.0,channels: Int=1,sample: (Double)->Double={.7*sin(it)}): File {
        val f=file();val samples=FloatArray((rate*seconds).toInt()*channels){i->(sample(2*PI*hz*(i/channels)/rate)*(if(channels==2&&i%2==1)-1 else 1)).toFloat()}
        FloatWaveWriter(f,rate,channels).use{it.write(samples,samples.size)};return f
    }
    @Test fun exactFloatSelectionPreservesValuesAndRate(){
        val source=tone(55.0,44100,channels=2);val cut=file()
        WaveFile(source).use{wave->wave.extract(cut,137,10097);WaveFile(cut).use{out->assertEquals(44100,out.rate);assertEquals(9960L,out.frames)
            val original=FloatArray(9960*2);val extracted=FloatArray(original.size);wave.read(137,9960,original);out.read(0,9960,extracted);assertArrayEquals(original,extracted,0f)}}
    }
    @Test fun monoDuplicatesToStereoWithoutChangingAmplitude(){val f=tone(110.0);WaveFile(f).use{w->val b=FloatArray(1000);assertEquals(500,w.read(234,500,b));repeat(500){assertEquals(b[2*it],b[2*it+1],0f)}}}
    @Test fun fragmentedWritesAndCheckpointSurviveRecovery(){
        val f=file();val writer=FloatWaveWriter(f,48000,1);val data=FloatArray(91){it/100f};writer.write(data,data.size);writer.checkpoint();writer.write(data,data.size)
        // Simulate a stale but intact header, followed by aligned samples on disk.
        assertTrue(FloatWaveWriter.recover(f));WaveFile(f).use{assertEquals(182L,it.frames)};writer.close()
    }
    @Test fun recoverDropsIncompleteStereoFrame(){val f=tone(60.0,seconds=.01,channels=2);val frames=WaveFile(f).use{it.frames};RandomAccessFile(f,"rw").use{it.seek(it.length());it.write(byteArrayOf(1,2,3,4))};assertTrue(FloatWaveWriter.recover(f));WaveFile(f).use{assertEquals(frames,it.frames)}}
    @Test fun invalidRecoveryDoesNotRewriteAnUnrelatedFile(){val f=file();f.writeText("This is not a WAV file. ".repeat(8));val before=f.readBytes();assertFalse(FloatWaveWriter.recover(f));assertArrayEquals(before,f.readBytes())}
    @Test fun rejectsTruncatedPayload(){val f=tone(55.0);RandomAccessFile(f,"rw").use{it.setLength(it.length()-4)};assertThrows(IllegalArgumentException::class.java){WaveFile(f)}}
    @Test fun rejectsNonFiniteFloatInput(){val f=file();FloatWaveWriter(f,48000,1).use{w->assertThrows(IllegalArgumentException::class.java){w.write(floatArrayOf(Float.NaN),1)}}}
    @Test fun optionalFadesOnlyAffectEdges(){val f=tone(80.0,seconds=.1,sample={.5});val cut=file();WaveFile(f).use{it.extract(cut,0,4800,96)};WaveFile(cut).use{val b=FloatArray(9600);it.read(0,4800,b);assertEquals(0f,b[0],0f);assertEquals(.5f,b[200],0f);assertEquals(0f,b.last(),0f)}}
    @Test fun envelopeFindsPeaksAtBoundaries(){val f=file();val data=FloatArray(1000){0f}.also{it[249]=1f;it[250]=-1f};FloatWaveWriter(f,48000,1).use{it.write(data,data.size)};WaveFile(f).use{val e=it.envelope(0,1000,4);assertEquals(1f,e[1],0f);assertEquals(-1f,e[2],0f)}}
    @Test fun sampleLevelEnvelopeRepresentsEveryPixel(){val f=file();FloatWaveWriter(f,48000,1).use{it.write(floatArrayOf(.25f,-.5f),2)};WaveFile(f).use{val e=it.envelope(0,2,8);assertEquals(.25f,e[0],0f);assertEquals(.25f,e[6],0f);assertEquals(-.5f,e[8],0f);assertEquals(-.5f,e[14],0f)}}
    @Test fun largeFileWindowDoesNotLoadWholeTake(){val f=file();val bytes=700L*1024*1024;RandomAccessFile(f,"rw").use{it.write(FloatWaveWriter.header(48000,1,bytes));it.setLength(bytes+44)};WaveFile(f).use{assertEquals(bytes/4,it.frames);val e=it.envelope(it.frames-480,it.frames,240);assertTrue(e.all{v->v==0f})}}
    @Test fun bassPitchAcrossSourceRates(){for(rate in listOf(44100,48000,96000)){WaveFile(tone(55.0,rate)).use{val r=BassAnalysis.analyze(it,0,it.frames);assertEquals(55.0,r.hz!!,.12);assertEquals("A1",r.note);assertEquals(33,r.midi);assertTrue(r.confidence>.98);assertEquals(128,r.spectrum.size)}}}
    @Test fun distortedBassRetainsFundamental(){WaveFile(tone(46.2493,sample={.2*sin(it)+.6*sin(2*it)+.2*sin(3*it)})).use{val r=BassAnalysis.analyze(it,0,it.frames);assertEquals(46.2493,r.hz!!,.15);assertEquals("F♯1",r.note)}}
    @Test fun antiphaseStereoStillFindsBass(){WaveFile(tone(65.4064,channels=2)).use{val r=BassAnalysis.analyze(it,0,it.frames);assertEquals(65.4064,r.hz!!,.15);assertEquals("C2",r.note)}}
    @Test fun silenceHasNoPitch(){WaveFile(tone(55.0,sample={0.0})).use{val r=BassAnalysis.analyze(it,0,it.frames);assertNull(r.hz);assertEquals("No stable pitch",r.note);assertEquals(0.0,r.rms,0.0)}}
    @Test fun whiteNoiseDoesNotInventANote(){val random=java.util.Random(7);WaveFile(tone(55.0,sample={random.nextDouble()-.5})).use{assertNull(BassAnalysis.analyze(it,0,it.frames).hz)}}
    @Test fun tooShortBassSelectionHasNoConfidentPitch(){WaveFile(tone(40.0,seconds=.01)).use{assertNull(BassAnalysis.analyze(it,0,it.frames).hz)}}
    @Test fun analysisCapsMemoryAndDuration(){WaveFile(tone(82.4069,seconds=3.0)).use{val r=BassAnalysis.analyze(it,4800,it.frames);assertEquals(2.0,r.seconds,0.0);assertEquals(82.4069,r.hz!!,.15)}}
    @Test fun cancelledExtractionDeletesPartialFile(){val f=tone(55.0);val out=file();WaveFile(f).use{assertThrows(IllegalStateException::class.java){it.extract(out,0,it.frames){error("Cancelled")}}};assertFalse(out.exists())}
    @Test fun invalidSelectionRejected(){WaveFile(tone(55.0)).use{w->assertThrows(IllegalArgumentException::class.java){w.envelope(5,5)};assertThrows(IllegalArgumentException::class.java){w.extract(file(),-1,40)};assertThrows(IllegalArgumentException::class.java){BassAnalysis.analyze(w,0,w.frames+1)}}}
    @Test fun reads24BitWithOddChunkPadding(){
        val f=file();val b=ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".toByteArray()).putInt(52).put("WAVEJUNK".toByteArray()).putInt(1).put(0).put(0)
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(48000).putInt(144000).putShort(3).putShort(24).put("data".toByteArray()).putInt(6).put(byteArrayOf(0,0,64,0,0,-64));f.writeBytes(b.array())
        WaveFile(f).use{w->val values=FloatArray(4);w.read(0,2,values);assertArrayEquals(floatArrayOf(.5f,.5f,-.5f,-.5f),values,0f)}
    }
}
