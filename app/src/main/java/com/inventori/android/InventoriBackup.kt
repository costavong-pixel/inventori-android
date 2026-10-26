package com.inventori.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Portable, user-controlled backup. No automatic upload or account claims. */
object InventoriBackup {
    private const val DB = "inventori.db"
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    fun export(context: Context, uri: Uri, database: InventoryDatabase) {
        // Close SQLite before snapshotting to ensure committed transactions are included.
        database.close()
        val dbFile = context.getDatabasePath(DB)
        require(dbFile.isFile) { "No inventory database found" }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                fun add(file: File, name: String) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JSONObject().put("format", "inventori-backup").put("version", 1)
                    .put("created_at", System.currentTimeMillis()).toString().toByteArray())
                zip.closeEntry()
                add(dbFile, DB)
                File(context.filesDir, "media").listFiles()?.filter { it.isFile }?.forEach {
                    add(it, "media/" + it.name)
                }
            }
        } ?: error("Could not open backup destination")
    }

    /** Restore replaces local data. Caller must obtain explicit destructive confirmation. */
    fun restore(context: Context, uri: Uri, database: InventoryDatabase) {
        val staging = File(context.cacheDir, "restore-" + System.nanoTime()).apply { mkdirs() }
        try {
            var total = 0L
            var hasManifest = false
            var hasDatabase = false
            val mediaNames = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        require(!entry.isDirectory && (name == "manifest.json" || name == DB ||
                            (name.startsWith("media/") && name.removePrefix("media/").matches(Regex("[A-Za-z0-9._-]{1,180}"))))) {
                            "Unexpected entry in backup"
                        }
                        val destination = File(staging, name)
                        require(!destination.exists()) { "Duplicate backup entry" }
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= MAX_TOTAL_BYTES) { "Backup exceeds 256 MB safety limit" }
                                output.write(buffer, 0, n)
                            }
                        }
                        if (name == "manifest.json") {
                            val manifest = JSONObject(destination.readText())
                            require(manifest.optString("format") == "inventori-backup" &&
                                manifest.optInt("version") == 1) { "Not a supported Inventori backup" }
                            hasManifest = true
                        }
                        if (name == DB) hasDatabase = true
                        if (name.startsWith("media/")) mediaNames.add(name.removePrefix("media/"))
                        zip.closeEntry()
                    }
                }
            } ?: error("Unable to read selected backup")
            require(hasManifest && hasDatabase) { "Backup is incomplete" }
            val stagedDb = File(staging, DB)
            val checkDb = SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                checkDb.rawQuery("PRAGMA integrity_check", null).use {
                    require(it.moveToFirst() && it.getString(0) == "ok") { "Backup database is corrupt" }
                }
                checkDb.rawQuery("PRAGMA user_version", null).use {
                    require(it.moveToFirst() && it.getInt(0) in 1..3) { "Unsupported database version" }
                }
                for (table in listOf("items", "locations", "workspaces")) {
                    checkDb.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                        require(it.moveToFirst()) { "Missing table: $table" }
                    }
                }
            } finally {
                checkDb.close()
            }
            // Validation has completed; only now replace local data.
            database.close()
            val destinationDb = context.getDatabasePath(DB)
            destinationDb.parentFile?.mkdirs()
            context.deleteDatabase(DB)
            stagedDb.copyTo(destinationDb, overwrite = true)
            val targetMedia = File(context.filesDir, "media").apply { mkdirs() }
            File(staging, "media").listFiles()?.forEach {
                it.copyTo(File(targetMedia, it.name), overwrite = true)
            }
            // Remap file references when importing from a different Android device.
            val db = SQLiteDatabase.openDatabase(destinationDb.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                for (field in listOf("photo_path", "receipt_path", "document_path")) {
                    db.rawQuery("SELECT id, $field FROM items WHERE $field IS NOT NULL", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val original = cursor.getString(1)
                            val filename = File(original).name
                            val target = if (filename in mediaNames || File(targetMedia, filename).exists())
                                File(targetMedia, filename).absolutePath else null
                            val cv = android.content.ContentValues()
                            if (target == null) cv.putNull(field) else cv.put(field, target)
                            db.update("items", cv, "id=?", arrayOf(cursor.getLong(0).toString()))
                        }
                    }
                }
            } finally { db.close() }
        } finally { staging.deleteRecursively() }
    }
}
