package com.weskestis.astrastudio.astra

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** User-owned credential, encrypted with a non-exportable Android Keystore key; never bundled. */
class GeminiKeyStore(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"astra-gemini-key"))
    private val alias="astra-gemini-user-key-v1"
    fun present()=file.baseFile.exists()
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(raw: String) {
        val value=raw.trim();require(value.matches(Regex("[A-Za-z0-9_-]{20,256}"))){"Enter a valid Google AI Studio API key."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes=value.toByteArray(Charsets.UTF_8)
        val encrypted=try{cipher.doFinal(bytes)}finally{bytes.fill(0)}
        val b64=Base64.getEncoder();val encoded=JSONObject().put("version",1).put("iv",b64.encodeToString(cipher.iv)).put("data",b64.encodeToString(encrypted)).toString().toByteArray()
        val out=file.startWrite();try{out.write(encoded);file.finishWrite(out)}catch(e: Exception){file.failWrite(out);throw e}
    }
    fun read(): String {
        check(present()){ "Add your free-tier Google AI Studio key in Astra settings first." }
        try{
            val data=JSONObject(String(file.readFully(),Charsets.UTF_8));require(data.getInt("version")==1)
            val b64=Base64.getDecoder();val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b64.decode(data.getString("iv"))))
            val bytes=cipher.doFinal(b64.decode(data.getString("data")));return try{String(bytes,Charsets.UTF_8)}finally{bytes.fill(0)}
        }catch(e: Exception){error("The saved Gemini key could not be unlocked. Remove it and enter it again.")}
    }
    fun clear(){file.delete();val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};if(store.containsAlias(alias))store.deleteEntry(alias)}
}

class GeminiClient {
    private val cancelled=AtomicBoolean(false)
    @Volatile private var connection: HttpsURLConnection?=null
    fun cancel(){cancelled.set(true);connection?.disconnect()}
    fun execute(key: String, body: String): String {
        check(!cancelled.get()){ "Cancelled." }
        val bytes=body.toByteArray(Charsets.UTF_8);require(bytes.size<=12*1024*1024){"Gemini request is too large. Use a smaller audio file."}
        val conn=URL("https://generativelanguage.googleapis.com/v1beta/models/${GeminiProtocol.MODEL}:generateContent").openConnection() as HttpsURLConnection
        connection=conn
        try {
            conn.requestMethod="POST";conn.connectTimeout=20000;conn.readTimeout=90000;conn.instanceFollowRedirects=false;conn.doOutput=true
            conn.setRequestProperty("x-goog-api-key",key);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setFixedLengthStreamingMode(bytes.size)
            check(!cancelled.get()){ "Cancelled." };conn.outputStream.use{it.write(bytes)}
            val code=conn.responseCode
            if(code!=200)error(when(code){
                400->"Gemini rejected the request. Check the audio format or try a shorter text request."
                401,403->"Gemini access was denied. Check your AI Studio key, project permissions and regional availability."
                404->"The configured Gemini model is unavailable for this project. Check Google's model availability."
                429->"Gemini's quota or rate limit was reached. Wait and retry; Astra will not switch to a paid model."
                in 500..599->"Gemini is temporarily unavailable. Try again later."
                else->"Gemini request failed (HTTP $code). Nothing was changed."
            })
            val raw=conn.inputStream.use{bounded(it,GeminiProtocol.MAX_RESPONSE_BYTES,cancelled)}
            check(!cancelled.get()){ "Cancelled." };return GeminiProtocol.response(String(raw,Charsets.UTF_8))
        }catch(e: java.net.SocketTimeoutException){error("Gemini timed out. Try a shorter request; nothing changed.")}
        catch(e: java.io.IOException){error(if(cancelled.get())"Cancelled." else "Could not reach Gemini. Check your internet connection and retry.")}
        finally{conn.disconnect();connection=null;bytes.fill(0)}
    }
    companion object {
        fun bounded(input: InputStream, limit: Int, cancelled: AtomicBoolean=AtomicBoolean(false)): ByteArray {
            val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
            while(true){check(!cancelled.get()){ "Cancelled." };val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit){"The file exceeds the ${limit/1024/1024} MiB limit."};out.write(buffer,0,n)}
            return out.toByteArray()
        }
        fun readAudio(context: Context, uri: Uri): AudioAttachment {
            require(uri.scheme=="content"){"Choose an audio file with Android's file picker."}
            val resolver=context.contentResolver;var name="Selected audio"
            resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->if(cursor.moveToFirst()){
                val ni=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(ni>=0)name=cursor.getString(ni)?.filter{!it.isISOControl()}?.take(128)?:name
                val si=cursor.getColumnIndex(OpenableColumns.SIZE);if(si>=0&&!cursor.isNull(si))require(cursor.getLong(si)<=GeminiProtocol.MAX_AUDIO_BYTES){"For Gemini listening, choose a file up to 8 MiB. MP3 works well for full tracks; DAW import has its own larger limit."}
            }}
            val given=resolver.getType(uri)?.lowercase()
            val mime=when(given){"audio/x-wav","audio/wave","audio/vnd.wave"->"audio/wav";"audio/x-flac"->"audio/flac";"audio/x-m4a","audio/mp4"->"audio/m4a";"audio/x-aiff"->"audio/aiff";else->given}
                ?.takeIf{it in AudioAttachment.mimeTypes}?:when(name.substringAfterLast('.').lowercase()){
                    "wav"->"audio/wav";"mp3"->"audio/mpeg";"flac"->"audio/flac";"ogg","opus"->"audio/ogg";"aac"->"audio/aac";"m4a"->"audio/m4a";"aif","aiff"->"audio/aiff";"webm"->"audio/webm";else->error("Choose WAV, MP3, FLAC, Ogg, AAC, M4A, AIFF or WebM audio.")}
            val bytes=requireNotNull(resolver.openInputStream(uri)){"Could not open that audio file."}.use{bounded(it,GeminiProtocol.MAX_AUDIO_BYTES)}
            require(bytes.isNotEmpty()){ "The audio file is empty." };return AudioAttachment(name,mime,bytes)
        }
    }
}
