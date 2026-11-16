package com.weskestis.astrastudio.recording

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Bounded-memory, seekable PCM reader. Never called from the render callback. */
class WaveFile(val file: File) : Closeable {
    private val input=RandomAccessFile(file,"r")
    val rate: Int
    val channels: Int
    val cycle: Int
    val frames: Long
    private val format: Int
    private val bits: Int
    private val offset: Long
    private val width: Int
    private val bytes=ByteArray(8192*8)
    init {
        try {
            fun word()=Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            fun short()=java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            fun tag()=ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            require(input.length()>=44&&tag()=="RIFF"){"Choose a PCM WAV recording."};val riffEnd=word()+8;require(riffEnd in 44..input.length()){ "Truncated RIFF container." };require(tag()=="WAVE")
            var tableCycle=0;var f=0;var c=0;var r=0;var b=0;var align=0;var start=-1L;var size=0L;var chunks=0
            while(input.filePointer+8<=riffEnd&&++chunks<=10000){
                val id=tag();val n=word();val p=input.filePointer
                require(n<=riffEnd-p){"Truncated WAV file."}
                if(id=="fmt "){
                    require(n>=16);f=short();c=short();r=word().toInt();word();align=short();b=short()
                    if(f==65534){require(n>=40);val extra=short();val valid=short();word();f=short()
                        val guidTail=ByteArray(14).also(input::readFully)
                        require(extra>=22&&valid==b&&guidTail.contentEquals(byteArrayOf(0,0,0,0,16,0,-128,0,0,-86,0,56,-101,113))){"Unsupported extensible WAV format."}}
                } else if(id=="clm "&&n in 6..256){val text=ByteArray(n.toInt()).also(input::readFully).toString(Charsets.US_ASCII);tableCycle=Regex("^<!>([0-9]{1,4}) ").find(text)?.groupValues?.get(1)?.toIntOrNull()?:0
                } else if(id=="data"&&start<0){start=p;size=n}
                input.seek(p+n+(n and 1))
            }
            require(f==1&&b in listOf(16,24,32)||f==3&&b==32){"Use 16/24/32-bit PCM or float WAV."}
            require(c in 1..2&&r in 8000..384000&&start>=0&&size>0&&align==c*(b/8)&&size%align==0L){"Invalid WAV layout."}
            format=f;channels=c;rate=r;bits=b;offset=start;width=align;frames=size/align
            cycle=tableCycle.takeIf{it in 32..2048&&it and (it-1)==0&&c==1&&frames%it==0L&&frames/it in 1..256}?:0
        } catch(e: Exception){input.close();throw e}
    }
    fun read(first: Long,count: Int,out: FloatArray): Int {
        require(first in 0..frames&&count>=0&&out.size>=count.toLong()*2)
        val n=min(count.toLong(),frames-first).toInt();input.seek(offset+first*width);var done=0
        while(done<n){val batch=min(n-done,bytes.size/width);input.readFully(bytes,0,batch*width);val buffer=ByteBuffer.wrap(bytes,0,batch*width).order(ByteOrder.LITTLE_ENDIAN)
            fun sample(): Float=when {format==3->buffer.float;bits==16->buffer.short/32768f;bits==32->buffer.int/2147483648f;else->{val v=(buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16);v/8388608f}}
            repeat(batch){val l=sample();val r=if(channels==2)sample() else l;require(l.isFinite()&&r.isFinite()){ "WAV contains invalid samples." };out[2*done]=l;out[2*done+1]=r;done++}
        };return n
    }
    fun envelope(first: Long,last: Long,buckets: Int=768,check: ()->Unit={}): FloatArray {
        require(first>=0&&last in (first+1)..frames&&buckets in 1..4096)
        val result=FloatArray(buckets*2);val seen=BooleanArray(buckets);val data=FloatArray(8192*2);var pos=first
        while(pos<last){check();val n=read(pos,min(8192L,last-pos).toInt(),data)
            repeat(n){i->val k=min(buckets-1,((pos+i-first)*buckets/(last-first)).toInt());val lo=min(data[i*2],data[i*2+1]);val hi=max(data[i*2],data[i*2+1])
                if(!seen[k]){result[2*k]=lo;result[2*k+1]=hi;seen[k]=true}else{result[2*k]=min(result[2*k],lo);result[2*k+1]=max(result[2*k+1],hi)}};pos+=n
        }
        // At sample-level zoom, repeat a frame over its corresponding screen pixels.
        if(last-first<buckets){val values=FloatArray((last-first).toInt()*2);read(first,(last-first).toInt(),values)
            repeat(buckets){k->val i=(k*(last-first)/buckets).toInt();result[2*k]=min(values[2*i],values[2*i+1]);result[2*k+1]=max(values[2*i],values[2*i+1])}}
        return result
    }
    fun extract(output: File,first: Long,last: Long,fadeFrames: Int=0,check: ()->Unit={}) {
        require(first>=0&&last in (first+1)..frames&&fadeFrames>=0)
        val data=FloatArray(8192*2);val fade=min(fadeFrames.toLong(),(last-first)/2);var pos=first
        try{FloatWaveWriter(output,rate,2).use{writer->while(pos<last){check();val n=read(pos,min(8192L,last-pos).toInt(),data)
            if(fade>0)repeat(n){i->val gain=min(1.0,min((pos+i-first).toDouble()/fade,(last-1-pos-i).toDouble()/fade)).toFloat();data[2*i]*=gain;data[2*i+1]*=gain}
            writer.write(data,n*2);pos+=n}}}catch(e: Exception){output.delete();throw e}
    }
    override fun close()=input.close()
}

/** Writes float samples incrementally and checkpoints the RIFF header for recovery. */
class FloatWaveWriter(val file: File,val rate: Int,val channels: Int) : Closeable {
    private val output=RandomAccessFile(file,"rw")
    private val bytes=ByteArray(65536)
    private val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var samples=0L;private set
    init{require(rate in 8000..384000&&channels in 1..2);output.setLength(0);output.write(ByteArray(44));checkpoint()}
    fun write(data: FloatArray,count: Int){require(count in 0..data.size&&count%channels==0&&samples+count<=(0xffffffffL-36)/4)
        var i=0;while(i<count){buffer.clear();val n=min(count-i,bytes.size/4);repeat(n){val v=data[i++];require(v.isFinite());buffer.putFloat(v)};output.write(bytes,0,n*4)};samples+=count
    }
    fun checkpoint(){val position=output.filePointer;output.seek(0);output.write(header(rate,channels,samples*4));output.seek(max(44,position));output.fd.sync()}
    override fun close(){try{checkpoint()}finally{output.close()}}
    companion object {
        fun header(rate: Int,channels: Int,bytes: Long): ByteArray=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt((bytes+36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(3).putShort(channels.toShort())
            putInt(rate).putInt(rate*channels*4).putShort((channels*4).toShort()).putShort(32).put("data".toByteArray()).putInt(bytes.toInt())
        }.array()
        fun recover(file: File): Boolean=runCatching{RandomAccessFile(file,"rw").use{f->
            require(f.length()>44);val h=ByteArray(44).also(f::readFully);val b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val rate=b.getInt(24);val channels=b.getShort(22).toInt()
            require(String(h,0,4)=="RIFF"&&String(h,8,8)=="WAVEfmt "&&String(h,36,4)=="data"&&b.getShort(20).toInt()==3&&b.getShort(34).toInt()==32&&channels in 1..2&&rate in 8000..384000)
            val bytes=(f.length()-44)/(channels*4)*(channels*4);require(bytes>0&&bytes<=0xffffffffL-36);f.setLength(bytes+44);f.seek(0);f.write(header(rate,channels,bytes));f.fd.sync()
        };true}.getOrDefault(false)
    }
}
