package com.weskestis.astrastudio.project

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** All file/codec methods run on the dedicated project I/O worker. */
class ProjectFiles(private val context: Context) {
    val audioDir=File(context.filesDir,"audio").also{it.mkdirs()}
    private val autosave=AtomicFile(File(context.filesDir,"current.astra.json"))
    fun audioFile(asset: AudioAsset)=File(audioDir,asset.file)
    fun load(): ProjectDocument? = if(!autosave.baseFile.exists())null else autosave.openRead().use{ProjectCodec.decode(readLimited(it,ProjectCodec.MAX_JSON_BYTES.toLong()).toString(Charsets.UTF_8))}
    fun preserveFailedAutosave(){if(autosave.baseFile.exists())autosave.baseFile.copyTo(File(context.filesDir,"recovery-${System.currentTimeMillis()}.astra.json"))}
    fun save(d: ProjectDocument) {
        val out=autosave.startWrite()
        try{out.write(ProjectCodec.encode(d).toByteArray(Charsets.UTF_8));autosave.finishWrite(out)}catch(e: Exception){autosave.failWrite(out);throw e}
    }
    fun displayName(uri: Uri): String=runCatching{context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:uri.lastPathSegment?:"Imported audio"
    fun importAudio(uri: Uri,cancel: AtomicBoolean,limit: Long=MAX_MEDIA_BYTES,directory: File=audioDir): Pair<String,File> {
        val name=displayName(uri)
        val source=File(context.cacheDir,"import-${UUID.randomUUID()}.bin")
        val dest=File(directory,"${UUID.randomUUID()}.wav")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use{input->source.outputStream().use{copy(input,it,limit,cancel)}}
            if(isPcmWave(source))source.inputStream().use{input->dest.outputStream().use{copy(input,it,limit,cancel)}} else decodeAudio(source,dest,cancel,limit)
            return name.take(128) to dest
        }catch(e: Exception){dest.delete();throw e}finally{source.delete()}
    }
    fun importPack(uri: Uri,limit: Int,cancel: AtomicBoolean): List<Pair<String,File>> {
        require(limit in 1..64){"The project sample browser is full (64 samples)."}
        val result=mutableListOf<Pair<String,File>>();val scratch=File(context.cacheDir,"pack-${UUID.randomUUID()}.bin")
        val discard=object: OutputStream(){override fun write(b: Int){};override fun write(b: ByteArray,off: Int,len: Int){}}
        try {
            var total=0L;var entries=0
            requireNotNull(context.contentResolver.openInputStream(uri)).use{input->ZipInputStream(BufferedInputStream(input)).use{zip->
                while(true){check(!cancel.get()){ "Cancelled." };val entry=zip.nextEntry?:break
                    require(++entries<=4096){"Pack contains too many entries."}
                    val name=entry.name.replace('\\','/').substringAfterLast('/')
                    val audio=!entry.isDirectory&&name.substringAfterLast('.',"").lowercase() in setOf("wav","mp3","ogg","flac","m4a","aac","aif","aiff","webm","mp4")
                    if(audio){
                        require(result.size<limit){"This pack exceeds the remaining $limit sample slots. Import a smaller selection."}
                        scratch.outputStream().use{total+=copy(zip,it,MAX_MEDIA_BYTES-total,cancel)}
                        val dest=File(audioDir,"${UUID.randomUUID()}.wav");result+=name.take(128) to dest
                        if(isPcmWave(scratch))scratch.inputStream().use{src->dest.outputStream().use{copy(src,it,MAX_MEDIA_BYTES,cancel)}} else decodeAudio(scratch,dest,cancel)
                    } else total+=copy(zip,discard,MAX_MEDIA_BYTES-total,cancel)
                    zip.closeEntry()
                }
            }}
            require(result.isNotEmpty()){ "No supported audio files found. Choose a ZIP sample pack, or select audio files directly." }
            return result
        }catch(e: Exception){result.forEach{it.second.delete()};throw e}finally{scratch.delete()}
    }
    fun saveArchive(d: ProjectDocument,uri: Uri,cancel: AtomicBoolean) {
        val used=d.project.assets.map{it.id}.toSet() // Preserve the sample browser, favorites and inactive pad assignments too.
        val portable=d.copy(project=d.project.copy(assets=d.project.assets.filter{it.id in used}))
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{out->ZipOutputStream(BufferedOutputStream(out)).use{zip->
            zip.putNextEntry(ZipEntry("project.json"));zip.write(ProjectCodec.encode(portable).toByteArray(Charsets.UTF_8));zip.closeEntry()
            for(a in portable.project.assets){zip.putNextEntry(ZipEntry("audio/${a.file}"));audioFile(a).inputStream().use{copy(it,zip,MAX_MEDIA_BYTES,cancel)};zip.closeEntry()}
        }}
    }
    fun openArchive(uri: Uri,cancel: AtomicBoolean): ProjectDocument {
        val folder=File(context.cacheDir,"project-${UUID.randomUUID()}").also{it.mkdirs()};val created=mutableListOf<File>()
        try{
            val seen=mutableSetOf<String>();var total=0L
            requireNotNull(context.contentResolver.openInputStream(uri)).use{input->ZipInputStream(BufferedInputStream(input)).use{zip->
                while(true){val entry=zip.nextEntry?:break;val name=entry.name
                    require(!entry.isDirectory&&(name=="project.json"||name.matches(Regex("audio/[A-Za-z0-9_-]{1,100}\\.wav")))&&seen.add(name)&&seen.size<=65){"Invalid or duplicate project archive entry."}
                    val out=File(folder,name);out.parentFile?.mkdirs()
                    out.outputStream().use{total+=copy(zip,it,if(name=="project.json")ProjectCodec.MAX_JSON_BYTES.toLong() else MAX_MEDIA_BYTES-total,cancel)}
                    require(total<=MAX_MEDIA_BYTES+ProjectCodec.MAX_JSON_BYTES){"Project archive is too large."};zip.closeEntry()
                }
            }}
            val json=File(folder,"project.json");require(json.isFile){"The archive has no project.json."}
            val d=ProjectCodec.decode(json.readText())
            require(seen==setOf("project.json")+d.project.assets.map{"audio/${it.file}"}){"Archive audio does not match its project."}
            val assets=d.project.assets.map{asset->val source=File(folder,"audio/${asset.file}");val target=File(audioDir,"${UUID.randomUUID()}.wav");created+=target
                source.inputStream().use{input->target.outputStream().use{copy(input,it,MAX_MEDIA_BYTES,cancel)}};asset.copy(file=target.name)}
            return d.copy(project=d.project.copy(assets=assets))
        }catch(e: Exception){created.forEach{it.delete()};throw e}finally{folder.deleteRecursively()}
    }
    fun publishWave(file: File,uri: Uri,cancel: AtomicBoolean) {
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{out->file.inputStream().use{copy(it,out,0xffffffffL,cancel)}}
    }
    private fun isPcmWave(file: File): Boolean = RandomAccessFile(file,"r").use{f->
        if(f.length()<44)return@use false
        val head=ByteArray(12);f.readFully(head);if(String(head,0,4,Charsets.US_ASCII)!="RIFF"||String(head,8,4,Charsets.US_ASCII)!="WAVE")return@use false
        repeat(10000){if(f.filePointer+8>f.length())return@use false
            val id=ByteArray(4);f.readFully(id);val size=Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
            if(String(id,Charsets.US_ASCII)=="fmt "){if(size<16)return@use false;val format=java.lang.Short.reverseBytes(f.readShort()).toInt() and 65535;return@use format in listOf(1,3,65534)}
            if(size>f.length()-f.filePointer)return@use false;f.seek(f.filePointer+size+(size and 1))
        };false
    }
    private fun decodeAudio(input: File,output: File,cancel: AtomicBoolean,limit: Long=MAX_MEDIA_BYTES) {
        val extractor=MediaExtractor();var codec: MediaCodec?=null
        try {
            extractor.setDataSource(input.absolutePath)
            val index=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
                ?:error("No audio track found.")
            extractor.selectTrack(index);val format=extractor.getTrackFormat(index);val mime=requireNotNull(format.getString(MediaFormat.KEY_MIME))
            format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_FLOAT)
            codec=MediaCodec.createDecoderByType(mime)
            try{codec.configure(format,null,null,0)}catch(_: Exception){codec.release();codec=MediaCodec.createDecoderByType(mime);format.removeKey(MediaFormat.KEY_PCM_ENCODING);codec.configure(format,null,null,0)}
            codec.start();var inputDone=false;var outputDone=false;var rate=0;var channels=0;var encoding=AudioFormat.ENCODING_PCM_16BIT;var bytes=0L
            var lastProgress=SystemClock.elapsedRealtime();val info=MediaCodec.BufferInfo();val payload=ByteArray(65536);val floats=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            RandomAccessFile(output,"rw").use{file->file.setLength(0);file.write(ByteArray(44))
                while(!outputDone){check(!cancel.get()){ "Cancelled." };check(SystemClock.elapsedRealtime()-lastProgress<30000){"The audio decoder stopped responding."}
                    if(!inputDone){val i=codec.dequeueInputBuffer(10000);if(i>=0){val b=requireNotNull(codec.getInputBuffer(i));val size=extractor.readSampleData(b,0)
                        if(size<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else {require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"Encrypted audio cannot be imported."};codec.queueInputBuffer(i,0,size,extractor.sampleTime,0);extractor.advance()}
                        lastProgress=SystemClock.elapsedRealtime()}}
                    when(val i=codec.dequeueOutputBuffer(info,10000)){
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{val f=codec.outputFormat;rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(rate in 8000..384000&&channels in 1..2){"Import supports mono or stereo audio."}}
                        else->if(i>=0){val b=requireNotNull(codec.getOutputBuffer(i)).order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size)
                            require(channels in 1..2&&rate>0){"Missing decoded audio format."}
                            val width=when(encoding){AudioFormat.ENCODING_PCM_FLOAT,AudioFormat.ENCODING_PCM_32BIT->4;AudioFormat.ENCODING_PCM_24BIT_PACKED->3;AudioFormat.ENCODING_PCM_16BIT->2;AudioFormat.ENCODING_PCM_8BIT->1;else->error("Unsupported decoded PCM format.")}
                            require(b.remaining()%(channels*width)==0)
                            fun sample(): Float = when(encoding){AudioFormat.ENCODING_PCM_FLOAT->b.float;AudioFormat.ENCODING_PCM_32BIT->b.int/2147483648f
                                AudioFormat.ENCODING_PCM_24BIT_PACKED->{var v=(b.get().toInt() and 255) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() shl 16);v=v shl 8 shr 8;v/8388608f}
                                AudioFormat.ENCODING_PCM_8BIT->((b.get().toInt() and 255)-128)/128f;else->b.short/32768f}
                            floats.clear()
                            while(b.hasRemaining()){val l=sample();val r=if(channels==2)sample()else l;require(l.isFinite()&&r.isFinite()){ "Audio contains invalid samples." }
                                floats.putFloat(l.coerceIn(-8f,8f));floats.putFloat(r.coerceIn(-8f,8f));if(floats.remaining()<8){file.write(payload,0,floats.position());bytes+=floats.position();floats.clear()}
                                require(bytes+floats.position()<=limit){"Decoded audio exceeds this import limit (${limit/(1024*1024)} MiB)."}}
                            if(floats.position()>0){file.write(payload,0,floats.position());bytes+=floats.position()}
                            outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(i,false);lastProgress=SystemClock.elapsedRealtime()
                        }
                    }
                }
                require(bytes>0);val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((bytes+36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(3).putShort(2)
                    .putInt(rate).putInt(rate*8).putShort(8).putShort(32).put("data".toByteArray()).putInt(bytes.toInt())
                file.seek(0);file.write(h.array())
            }
        }finally{runCatching{codec?.stop()};codec?.release();extractor.release()}
    }
    companion object {
        const val MAX_MEDIA_BYTES=512L*1024*1024
        private fun readLimited(input: InputStream,limit: Long): ByteArray=ByteArrayOutputStream().also{copy(input,it,limit,AtomicBoolean(false))}.toByteArray()
        fun copy(input: InputStream,output: OutputStream,limit: Long,cancel: AtomicBoolean): Long {
            val data=ByteArray(65536);var total=0L
            while(true){check(!cancel.get()){ "Cancelled." };val count=input.read(data);if(count<0)break;if(count==0)continue;total+=count;require(total<=limit){"File exceeds the supported size."};output.write(data,0,count)}
            return total
        }
    }
}
