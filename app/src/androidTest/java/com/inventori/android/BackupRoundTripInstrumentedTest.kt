package com.inventori.android

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Protects against distributing an APK that writes a PDF instead of a backup. */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripInstrumentedTest {
    @Test
    fun elevenItemsShareOnePhotoAndSurviveZipRestore() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase("inventori.db")
        val media = File(ctx.filesDir, "media").apply { mkdirs() }
        val photo = File(media, "roundtrip-photo.png")
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        var db = InventoryDatabase(ctx)
        val workspace = db.createWorkspace("Roundtrip", "home")
        repeat(11) { index ->
            db.saveItem(InventoryItem(workspaceId = workspace, name = "Test item $index", photoPath = photo.absolutePath))
        }
        val output = File(media, "roundtrip-backup.zip")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", output)
        InventoriBackup.export(ctx, uri, db)

        assertTrue("ZIP file was not written", output.isFile && output.length() > 0)
        ZipFile(output).use { zip ->
            assertTrue("SQLite database missing", zip.getEntry("inventori.db") != null)
            assertTrue("Manifest missing", zip.getEntry("manifest.json") != null)
            assertTrue("Shared photograph missing", zip.getEntry("media/roundtrip-photo.png") != null)
            val imageEntries = zip.entries().asSequence().filter { it.name.startsWith("media/") }.toList()
            assertEquals("Only one group photograph should be backed up", 1, imageEntries.size)
        }
        assertTrue(photo.delete())
        ctx.deleteDatabase("inventori.db")
        db = InventoryDatabase(ctx)
        InventoriBackup.restore(ctx, uri, db)
        db = InventoryDatabase(ctx)
        val saved = db.items(workspace)
        assertEquals(11, saved.size)
        assertEquals(1, saved.mapNotNull { it.photoPath }.distinct().size)
        assertTrue(saved.all { it.photoPath?.let(::File)?.isFile == true })
        db.close()
        output.delete()
        photo.delete()
    }
}
