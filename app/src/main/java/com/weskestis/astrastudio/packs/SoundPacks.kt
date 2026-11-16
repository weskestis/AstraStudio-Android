package com.weskestis.astrastudio.packs

import com.weskestis.astrastudio.recording.WaveFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.math.abs

data class PackSound(val packId: String,val part: Int,val id: String,val name: String,val category: String,
    val file: String,val sha256: String,val bytes: Long,val rate: Int,val channels: Int,val frames: Long,
    val cycle: Int=0,val bpm: Double=0.0,val key: String="",val peak: Float=0f) {
    val keyId get()="$packId:$id"
    val isTable get()=cycle>0
    val seconds get()=frames.toDouble()/rate
}
data class SoundPack(val id: String,val name: String,val creator: String,val part: Int,val parts: Int,
    val notice: String,val directory: String,val fingerprint: String,val sounds: List<PackSound>)
data class PackCatalog(val packs: List<SoundPack> = emptyList(),val favorites: Set<String> = emptySet(),val warning: String="") {
    val sounds get()=packs.flatMap{it.sounds}
    fun sound(key: String)=packs.firstNotNullOfOrNull{p->p.sounds.firstOrNull{it.keyId==key}}
    fun pack(sound: PackSound)=packs.first{it.id==sound.packId&&it.part==sound.part}
}

/** Separate on-disk library; installed sounds never occupy project/native sample slots until chosen.
 * All methods run on the service I/O worker, including validation, hashing and index publication. */
class SoundPacks(private val root: File) {
    init { check(root.mkdirs()||root.isDirectory) }
    private val favoritesFile=File(root,"favorites.json")
    private fun cancelled(cancel: AtomicBoolean){check(!cancel.get()){"Cancelled. Completed pack parts remain installed."}}
    fun catalog(): PackCatalog {
        val packs=mutableListOf<SoundPack>();val warnings=mutableListOf<String>()
        root.listFiles()?.filter{it.isDirectory&&!it.name.startsWith(".")}?.sortedBy{it.name}?.forEach{dir->
            try{val file=File(dir,"catalog.json");require(file.length() in 1..MAX_MANIFEST);packs+=decode(JSONObject(file.readText()),dir.name)}
            catch(_: Exception){warnings+="An installed pack index is unreadable; reinstall that part to repair it."}
        }
        val favorites=runCatching{if(!favoritesFile.exists())emptySet() else {require(favoritesFile.length()<=MAX_MANIFEST);val a=JSONArray(favoritesFile.readText());require(a.length()<=MAX_LIBRARY_SOUNDS);(0 until a.length()).map{a.getString(it)}.toSet()}}.getOrDefault(emptySet())
        return PackCatalog(packs.sortedWith(compareBy({it.name},{it.part})),favorites,warnings.distinct().joinToString(" "))
    }
    fun favorite(key: String): PackCatalog {
        val old=catalog();require(old.sound(key)!=null){"That sound is no longer installed."}
        val next=old.favorites.toMutableSet();if(!next.add(key))next.remove(key)
        atomicText(favoritesFile,JSONArray(next.sorted()).toString());return old.copy(favorites=next)
    }
    fun file(catalog: PackCatalog,sound: PackSound): File {
        val p=catalog.pack(sound);require(p.directory.matches(SAFE)&&sound.file.matches(Regex("[a-f0-9]{64}\\.wav")))
        return File(File(root,p.directory),sound.file).also{require(it.isFile&&it.length()==sound.bytes){"Pack audio is missing or changed. Reinstall ${p.name}, part ${p.part}."}}
    }
    fun install(input: InputStream,title: String,cancel: AtomicBoolean,progress: (String,Float)->Unit={_,_->}): SoundPack {
        val staging=File(root,".install-${UUID.randomUUID()}").also{check(it.mkdir())};val archive=File(staging,"source.zip")
        try {
            progress("Reading $title",0f)
            FileOutputStream(archive).use{out->copy(input,out,MAX_ARCHIVE,cancel);out.fd.sync()}
            return ZipFile(archive).use{zip->
                val entries=zip.entries().asSequence().toList();require(entries.size in 1..MAX_ENTRIES){"Pack has too many entries."}
                require(entries.map{it.name}.toSet().size==entries.size){"Duplicate archive paths."}
                entries.forEach{require(safePath(it.name)&&it.size>=0&&it.size<=MAX_FILE){"Unsafe or oversized archive entry."}}
                require(entries.sumOf{it.size}<=MAX_EXPANDED){"Pack expands beyond 4 GiB."}
                val manifestEntry=zip.getEntry("manifest.json")
                val manifest=manifestEntry?.let{e->require(e.size in 1..MAX_MANIFEST);JSONObject(zip.getInputStream(e).use{String(read(it,MAX_MANIFEST,cancel),Charsets.UTF_8)})}
                if(manifest!=null)require(manifest.getString("format")=="rift-sound-pack"&&manifest.getInt("version")==1){"Choose a RIFT sound pack or a WAV ZIP."}
                val digest=sha256(if(manifest!=null)manifest.toString().toByteArray() else archive.inputStream().use{hashBytes(it,cancel)})
                val packId=manifest?.getString("id")?:"zip-${digest.take(24)}"
                require(packId.matches(SAFE)&&packId.length<=80)
                val part=manifest?.getInt("part")?:1;val parts=manifest?.getInt("parts")?:1;require(parts in 1..100&&part in 1..parts)
                val name=(manifest?.getString("name")?:title.substringBeforeLast('.')).clean(160)
                val creator=(manifest?.optString("creator")?:"").clean(160,true)
                val notice=(manifest?.optString("licenseNotice")?:"").take(16000)
                val directory="$packId-$part";val dest=File(root,directory)
                val list=if(manifest!=null)manifest.getJSONArray("entries").let{a->require(a.length() in 1..MAX_ENTRIES);(0 until a.length()).map{a.getJSONObject(it)}} else entries.filter{!it.isDirectory&&it.name.endsWith(".wav",true)}.map{e->JSONObject().put("id",sha256(e.name.toByteArray()).take(32)).put("kind","sample").put("file",e.name).put("name",e.name.substringAfterLast('/').substringBeforeLast('.')).put("category",e.name.substringBeforeLast('/',"Unsorted"))}
                require(list.isNotEmpty()&&list.map{it.getString("file")}.toSet().size==list.size&&list.map{it.getString("id")}.toSet().size==list.size){"Empty pack or duplicate sound identifiers."}
                val installed=catalog();require(installed.sounds.size-installed.packs.filter{it.id==packId&&it.part==part}.sumOf{it.sounds.size}+list.count{it.optString("kind")!="document"}<=MAX_LIBRARY_SOUNDS){"Installed library holds up to 20,000 sounds."}
                require(root.usableSpace>=entries.sumOf{it.size}+16*1024*1024){"Not enough free storage to install this pack."}
                val sounds=mutableListOf<PackSound>();var expanded=0L
                list.forEachIndexed { index,m->
                    cancelled(cancel)
                    val kind=m.getString("kind");require(kind in setOf("sample","wavetable","document")){"Unsupported pack entry type: $kind"}
                    val path=m.getString("file");require(safePath(path));val entry=requireNotNull(zip.getEntry(path)){"Missing pack entry: $path"};require(!entry.isDirectory)
                    val id=m.getString("id");require(id.matches(SAFE)&&id.length<=80)
                    val temporary=File(staging,"entry.bin")
                    val hash=MessageDigest.getInstance("SHA-256");val crc=CRC32()
                    FileOutputStream(temporary).use{out->zip.getInputStream(entry).use{source->copy(source,out,minOf(MAX_FILE,MAX_EXPANDED-expanded),cancel,hash,crc)};out.fd.sync()}
                    val bytes=temporary.length();expanded+=bytes;val hex=hash.digest().hex()
                    require(bytes==entry.size&&crc.value==entry.crc){"Damaged ZIP entry: $path"}
                    if(manifest!=null)require(m.getLong("bytes")==bytes&&m.getString("sha256")==hex){"Pack integrity check failed: $path"}
                    if(kind=="document") {require(bytes<=MAX_MANIFEST);check(temporary.renameTo(File(staging,"doc-$hex.txt")))}
                    else {
                        var rate=0;var channels=0;var frames=0L;var peak=0f;var actualCycle=0
                        WaveFile(temporary).use{wave->rate=wave.rate;channels=wave.channels;frames=wave.frames;actualCycle=wave.cycle
                            val data=FloatArray(16384);var at=0L
                            while(at<frames){cancelled(cancel);val n=wave.read(at,minOf(8192L,frames-at).toInt(),data);repeat(n*2){peak=maxOf(peak,abs(data[it]))};at+=n}
                        }
                        require(frames>0&&frames<=268435456&&peak<=8f){"Unsupported sample range: $path"}
                        if(manifest!=null)require(m.getInt("rate")==rate&&m.getInt("channels")==channels&&m.getLong("frames")==frames){"Sample metadata mismatch: $path"}
                        val cycle=if(kind=="wavetable")m.getInt("cycle")else if(manifest==null)actualCycle else 0
                        require(cycle==0||cycle==actualCycle){"Wavetable cycle metadata does not match its WAV."}
                        require(cycle==0||(cycle in 32..2048&&cycle and (cycle-1)==0&&frames%cycle==0L&&frames/cycle in 1..256&&channels==1)){"Unsupported wavetable dimensions."}
                        val bpm=m.optDouble("bpm",0.0);require(bpm==0.0||(bpm.isFinite()&&bpm in 30.0..300.0))
                        val waveFile="$hex.wav";val target=File(staging,waveFile)
                        if(target.exists())temporary.delete()else check(temporary.renameTo(target))
                        sounds+=PackSound(packId,part,id,m.getString("name").clean(128),m.optString("category","Unsorted").clean(160),waveFile,hex,bytes,rate,channels,frames,cycle,bpm,m.optString("key").take(32),peak)
                    }
                    progress("$name · ${index+1}/${list.size}",(index+1).toFloat()/list.size)
                }
                require(sounds.isNotEmpty()){"No supported WAV sounds in this pack."}
                val result=SoundPack(packId,name,creator,part,parts,notice,directory,digest,sounds)
                atomicText(File(staging,"catalog.json"),encode(result).toString())
                archive.delete();cancelled(cancel)
                // A staged complete directory is swapped only after all content checks succeed.
                val backup=File(root,".previous-$directory")
                if(backup.exists())backup.deleteRecursively()
                if(dest.exists())check(dest.renameTo(backup)){"Could not replace the installed pack."}
                if(!staging.renameTo(dest)){if(backup.exists())backup.renameTo(dest);error("Could not finish installing the pack.")}
                backup.deleteRecursively();result
            }
        }finally{if(staging.exists())staging.deleteRecursively()}
    }
    fun repairInterruptedInstalls(){
        root.listFiles()?.filter{it.isDirectory&&it.name.startsWith(".previous-")}?.forEach{old->val name=old.name.removePrefix(".previous-");if(name.matches(SAFE)){val dest=File(root,name);if(!dest.exists())old.renameTo(dest)else old.deleteRecursively()}}
        root.listFiles()?.filter{it.isDirectory&&it.name.startsWith(".install-")}?.forEach{it.deleteRecursively()}
    }
    companion object {
        private val SAFE=Regex("[A-Za-z0-9_-]+")
        const val MAX_ENTRIES=4096;const val MAX_LIBRARY_SOUNDS=20000
        const val MAX_FILE=2L*1024*1024*1024;const val MAX_ARCHIVE=MAX_FILE;const val MAX_EXPANDED=4L*1024*1024*1024;const val MAX_MANIFEST=4L*1024*1024
        private fun String.clean(max: Int,empty: Boolean=false)=trim().also{require(it.length<=max&&(empty||it.isNotBlank())&&it.none(Char::isISOControl)){"Invalid pack label."}}
        private fun safePath(s: String)=s.isNotBlank()&&s.length<=1024&&!s.startsWith('/')&&!s.contains('\\')&&!s.contains(':')&&s.split('/').none{it==".."||it=="."}&&s.none(Char::isISOControl)
        private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
        private fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun hashBytes(input: InputStream,cancel: AtomicBoolean): ByteArray {val digest=MessageDigest.getInstance("SHA-256");copy(input,OutputStream.nullOutputStream(),MAX_ARCHIVE,cancel,digest);return digest.digest()}
        private fun copy(input: InputStream,out: OutputStream,limit: Long,cancel: AtomicBoolean,hash: MessageDigest?=null,crc: CRC32?=null): Long {
            val data=ByteArray(65536);var size=0L
            while(true){check(!cancel.get()){"Cancelled. Completed pack parts remain installed."};val n=input.read(data);if(n<0)break;if(n==0)continue;size+=n;require(size<=limit){"Pack exceeds the supported size."};hash?.update(data,0,n);crc?.update(data,0,n);out.write(data,0,n)};return size
        }
        private fun read(input: InputStream,limit: Long,cancel: AtomicBoolean)=ByteArrayOutputStream().also{copy(input,it,limit,cancel)}.toByteArray()
        private fun atomicText(file: File,text: String){val next=File(file.parentFile,".${file.name}.pending");try{FileOutputStream(next).use{it.write(text.toByteArray());it.fd.sync()};Files.move(next.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{next.delete()}}
        private fun encode(p: SoundPack)=JSONObject().put("id",p.id).put("name",p.name).put("creator",p.creator).put("part",p.part).put("parts",p.parts).put("notice",p.notice).put("fingerprint",p.fingerprint).put("sounds",JSONArray().also{a->p.sounds.forEach{s->a.put(JSONObject().put("id",s.id).put("name",s.name).put("category",s.category).put("file",s.file).put("sha256",s.sha256).put("bytes",s.bytes).put("rate",s.rate).put("channels",s.channels).put("frames",s.frames).put("cycle",s.cycle).put("bpm",s.bpm).put("key",s.key).put("peak",s.peak.toDouble()))}})
        private fun decode(o: JSONObject,dir: String): SoundPack {
            val id=o.getString("id");val part=o.getInt("part");val parts=o.getInt("parts");require(id.matches(SAFE)&&dir=="$id-$part"&&parts in 1..100&&part in 1..parts)
            val a=o.getJSONArray("sounds");require(a.length() in 1..MAX_ENTRIES)
            val sounds=(0 until a.length()).map{i->val s=a.getJSONObject(i);PackSound(id,part,s.getString("id"),s.getString("name").clean(128),s.getString("category").clean(160),s.getString("file"),s.getString("sha256"),s.getLong("bytes"),s.getInt("rate"),s.getInt("channels"),s.getLong("frames"),s.getInt("cycle"),s.getDouble("bpm"),s.getString("key"),s.getDouble("peak").toFloat()).also{
                require(it.id.matches(SAFE)&&it.sha256.matches(Regex("[0-9a-f]{64}"))&&it.file=="${it.sha256}.wav"&&it.bytes in 1..MAX_FILE&&it.rate in 8000..384000&&it.channels in 1..2&&it.frames in 1..268435456&&it.peak.isFinite()&&it.peak in 0f..8f&&it.bpm.isFinite()&&(it.bpm==0.0||it.bpm in 30.0..300.0))
                require(it.cycle==0||(it.cycle in 32..2048&&it.cycle and (it.cycle-1)==0&&it.frames%it.cycle==0L&&it.frames/it.cycle in 1..256&&it.channels==1))
            }};require(sounds.map{it.id}.toSet().size==sounds.size)
            return SoundPack(id,o.getString("name").clean(160),o.getString("creator").clean(160,true),part,parts,o.getString("notice").take(16000),dir,o.getString("fingerprint"),sounds)
        }
    }
}

data class PackDetail(val key: String,val waveform: List<Float>)
object PackPitch {
    /** Filename keys omit octave: use octave four, keep tuning editable. */
    fun root(key: String): Int {
        val note=Regex("^([A-Ga-g])([#b]?)").find(key)?:return 60
        val pitch=mapOf("C" to 0,"D" to 2,"E" to 4,"F" to 5,"G" to 7,"A" to 9,"B" to 11).getValue(note.groupValues[1].uppercase())
        return 60+(pitch+when(note.groupValues[2]){"#"->1;"b"->-1;else->0}+12)%12
    }
}
