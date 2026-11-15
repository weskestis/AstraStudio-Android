package com.weskestis.astrastudio

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.weskestis.astrastudio.audio.AudioBridge
import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.synth.SynthSchema
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val notifications=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @After fun captureFinalState(){runCatching{shot("99-last-screen")};runCatching{compose.onAllNodes(isRoot())[0].printToLog("ASTRA_UI_TEST")}}
    private fun shot(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val roots=compose.onAllNodes(isRoot())
        val bitmap=roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap()
        // UTP uninstalls the app after the test, removing its private directory.
        // MediaStore output survives that cleanup and is collected from the emulator.
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AstraStudio")
            put(android.provider.MediaStore.Images.Media.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);resolver.update(uri,values,null,null)
    }
    private fun click(tag: String){val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick()}
    @Test fun createEditUndoArrangeAndRotate(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("Load starter song");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithText("Kick 1").fetchSemanticsNodes().isNotEmpty()}
        click("Audio")
        compose.waitUntil(15000){compose.onAllNodesWithText("Audio on").fetchSemanticsNodes().isNotEmpty()}
        click("Play")
        compose.waitUntil(10000){compose.onAllNodesWithText("▸").fetchSemanticsNodes().isNotEmpty()}
        click("■ Stop")
        shot("01-session-portrait")
        click("Edit clip")
        compose.onNodeWithTag("Piano roll").performScrollTo().assertIsDisplayed().performTouchInput{click(Offset(width*.4f,height*.55f))}
        compose.waitForIdle();shot("02-piano-roll")
        click("Close panel");click("Undo");click("Redo")
        click("Arrangement");compose.onNodeWithTag("Arrangement timeline").assertIsDisplayed();shot("03-arrangement-portrait")
        compose.onNodeWithTag("Region").assertIsNotEnabled()
        compose.onNodeWithTag("Timeline canvas").performTouchInput{click(Offset(width*.6f,35f))}
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Region") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle();shot("03b-region-selection")
        compose.onNodeWithTag("Region").assertIsEnabled()
        click("Region")
        compose.waitUntil(10000){compose.onAllNodesWithText("Arrangement region").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Arrangement region").assertIsDisplayed();compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000){compose.onAllNodesWithTag("Arrangement timeline").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle();shot("04-arrangement-landscape")
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
    }
    @Test fun portableProjectAndNativeExportRoundTrip(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bridge=AudioBridge()
        try {
            val d=StarterProject.create(SynthSchema.presets.indices.map(bridge::preset))
            val io=ProjectFiles(context);val cancel=AtomicBoolean(false)
            val archive=File(context.cacheDir,"test-project.astra")
            io.saveArchive(d,Uri.fromFile(archive),cancel);val restored=io.openArchive(Uri.fromFile(archive),cancel);assertEquals(d,restored)
            val wave=File(context.cacheDir,"test-render.wav")
            // Short native fixture exercises the same exported code used by Oboe.
            val short=d.copy(project=d.project.copy(regions=d.project.regions.filter{it.slot==0}.take(1).map{it.copy(length=2.0)}))
            assertEquals(0,bridge.export(wave,short,48000,.1f));assertTrue(wave.length()>100000)
            val head=wave.inputStream().use{val b=ByteArray(44);assertEquals(44,it.read(b));b}
            assertEquals("RIFF",String(head,0,4));assertEquals(24,ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getShort(34).toInt())
            val imported=io.importAudio(Uri.fromFile(wave),cancel)
            assertTrue(bridge.loadAssets(listOf(0 to imported.second)));val info=bridge.assetInfo(0)!!;assertEquals(48000.0,info[0],0.0);assertEquals(1026,info.size)
            val asset=AudioAsset(0,"Rendered kick",imported.second.name,48000,info[1].toLong(),info.drop(2).map{it.toFloat()})
            val audioDoc=short.copy(project=Project(name="Audio round trip",assets=listOf(asset),clips=listOf(Clip(0,asset=0,last=asset.frames,length=2.0))).place(0,0.0))
            io.saveArchive(audioDoc,Uri.fromFile(archive),cancel);val reopened=io.openArchive(Uri.fromFile(archive),cancel)
            assertEquals(audioDoc.project.clips,reopened.project.clips);assertTrue(io.audioFile(reopened.project.assets.single()).readBytes().contentEquals(imported.second.readBytes()))
            val autosave=ProjectFiles(context);autosave.save(d);assertEquals(d,autosave.load())
            archive.delete();wave.delete();imported.second.delete();reopened.project.assets.forEach{io.audioFile(it).delete()}
        }finally{bridge.close()}
    }
}
