package com.weskestis.astrastudio.project

import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.recording.WaveFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class InstrumentPreset(val id: String,val name: String,val mode: Int,val sounds: Int,val bytes: Long)

/** Private, reusable instrument assets. All disk access is on the service I/O worker. */
class InstrumentLibrary(private val root: File) {
    init{check(root.mkdirs()||root.isDirectory)}
    private val safe=Regex("[a-f0-9]{32}")
    private fun folder(id: String): File {require(id.matches(safe));return File(root,id)}
    fun repair(){root.listFiles()?.filter{it.isDirectory&&it.name.startsWith(".save-")}?.forEach{it.deleteRecursively()}}
    fun list(): List<InstrumentPreset> = root.listFiles().orEmpty().filter{it.isDirectory&&it.name.matches(safe)}.mapNotNull{dir->
        runCatching{val f=File(dir,"meta.json");require(f.length() in 1..1048576);val m=JSONObject(f.readText());require(m.getInt("version")==1)
            InstrumentPreset(dir.name,m.getString("name"),m.getInt("mode"),m.getJSONArray("sources").length(),m.getLong("bytes"))}.getOrNull()
    }.sortedBy{it.name.lowercase()}
    fun delete(id: String){val dir=folder(id);require(dir.isDirectory){"That instrument is no longer saved."};check(dir.deleteRecursively()){"Could not remove this saved instrument."}}
    private fun copy(source: File,target: File,cancel: AtomicBoolean): Pair<Long,String> {
        require(source.isFile&&source.length() in 1..ProjectFiles.MAX_MEDIA_BYTES){"Instrument source is missing or too large."}
        val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
        source.inputStream().use{input->FileOutputStream(target).use{output->val buffer=ByteArray(65536)
            while(true){check(!cancel.get()){"Cancelled."};val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=ProjectFiles.MAX_MEDIA_BYTES);digest.update(buffer,0,n);output.write(buffer,0,n)};output.fd.sync()}}
        return bytes to digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun save(name: String,d: ProjectDocument,track: Int,source: (AudioAsset)->File,cancel: AtomicBoolean): InstrumentPreset {
        require(d.valid()&&track in 0 until ProjectLimits.TRACKS);val title=name.trim();require(title.isNotEmpty()&&title.length<=64&&title.none{it.isISOControl()}){"Give this instrument a name (1–64 characters)."}
        val instrument=d.project.instruments[track];require(instrument.mode in 1..2&&instrument.assets().isNotEmpty()){"Load a sampler sound or drum kit first."}
        require(list().size<256){"The instrument library holds 256 presets. Remove an unused preset first."}
        val assets=d.project.assets.filter{it.id in instrument.assets()};val bytes=assets.sumOf{source(it).length()};require(root.usableSpace>bytes+16777216){"Not enough free storage for an independent instrument copy."}
        val id=UUID.randomUUID().toString().replace("-","");val stage=File(root,".save-$id").also{check(it.mkdir())}
        try{
            val entries=JSONArray();val stored=assets.map{a->val file="source_${a.id}.wav";val dest=File(stage,file);val hash=copy(source(a),dest,cancel)
                WaveFile(dest).use{require(it.frames==a.frames&&it.rate==a.rate&&it.cycle==a.cycle){"Instrument audio metadata changed."}}
                entries.put(JSONObject().put("id",a.id).put("file",file).put("bytes",hash.first).put("sha256",hash.second));a.copy(file=file)}
            val project=Project(name=title,assets=stored).instrument(0){instrument}
            val saved=ProjectDocument(project,120f,.7f,0f,80f,List(ProjectLimits.TRACKS){TrackControls()},List(ProjectLimits.TRACKS){if(it==0)d.patches[track]else SynthPatch.initial()},List(ProjectLimits.TRACKS){if(it==0)d.effects[track]else FxConfig.initial()})
            FileOutputStream(File(stage,"project.json")).use{it.write(ProjectCodec.encode(saved).toByteArray());it.fd.sync()}
            val meta=JSONObject().put("version",1).put("name",title).put("mode",instrument.mode).put("bytes",bytes).put("sources",entries)
            FileOutputStream(File(stage,"meta.json")).use{it.write(meta.toString().toByteArray());it.fd.sync()}
            check(!cancel.get()){"Cancelled."};check(stage.renameTo(folder(id))){"Could not publish the saved instrument."}
            return InstrumentPreset(id,title,instrument.mode,assets.size,bytes)
        }finally{if(stage.exists())stage.deleteRecursively()}
    }
    fun apply(id: String,track: Int,d: ProjectDocument,audio: File,cancel: AtomicBoolean,validate: (ProjectDocument)->Unit={}): ProjectDocument {
        require(d.valid()&&track in 0 until ProjectLimits.TRACKS)
        val folder=folder(id);val json=File(folder,"project.json");val meta=File(folder,"meta.json")
        require(json.length() in 1..ProjectCodec.MAX_JSON_BYTES&&meta.length() in 1..1048576){"Saved instrument is missing or damaged."}
        val saved=ProjectCodec.decode(json.readText());require(saved.project.instruments[0].mode in 1..2);val m=JSONObject(meta.readText());require(m.getInt("version")==1)
        val entries=m.getJSONArray("sources");require(entries.length()==saved.project.assets.size)
        val hashes=mutableMapOf<Int,Pair<Long,String>>()
        repeat(entries.length()){i->val e=entries.getJSONObject(i);val source=e.getInt("id");val a=requireNotNull(saved.project.asset(source));val hash=e.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}"))&&e.getString("file")==a.file&&!hashes.containsKey(source));hashes[source]=e.getLong("bytes") to hash}
        val created=mutableListOf<File>();val assets=d.project.assets.toMutableList();val mapping=mutableMapOf<Int,Int>()
        try{
            for(a in saved.project.assets){check(!cancel.get()){"Cancelled."};val expected=hashes.getValue(a.id);val prefix="preset_${expected.second}_"
                val existing=assets.firstOrNull{it.file.startsWith(prefix)||it.file.startsWith("pack_${expected.second}_")}
                if(existing!=null){require(existing.rate==a.rate&&existing.frames==a.frames&&existing.cycle==a.cycle);mapping[a.id]=existing.id;continue}
                val next=(0..63).firstOrNull{n->assets.none{it.id==n}}?:error("This instrument needs more free project source slots (maximum 64).")
                val out=File(audio,"$prefix${UUID.randomUUID().toString().replace("-","").take(16)}.wav");created+=out
                require(audio.usableSpace>expected.first+16777216){"Not enough storage to load this instrument."}
                require(copy(File(folder,a.file),out,cancel)==expected){"Saved instrument audio failed its integrity check."}
                WaveFile(out).use{require(it.rate==a.rate&&it.frames==a.frames&&it.cycle==a.cycle)}
                assets+=a.copy(id=next,file=out.name,group=m.getString("name"));mapping[a.id]=next
            }
            val next=d.copy(project=d.project.copy(assets=assets).instrument(track){saved.project.instruments[0].mapAssets(mapping)},
                patches=d.patches.toMutableList().also{it[track]=saved.patches[0]},effects=d.effects.toMutableList().also{it[track]=saved.effects[0]})
            require(next.valid()){"This instrument exceeds the project source budget."};check(!cancel.get()){"Cancelled."};validate(next);return next
        }catch(e: Exception){created.forEach{it.delete()};throw e}
    }
}
