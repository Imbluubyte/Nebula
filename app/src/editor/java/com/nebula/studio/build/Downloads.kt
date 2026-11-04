package com.nebula.studio.build

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Copies a finished APK into the device's public Downloads folder (MediaStore: no storage permission needed on Android 11+). */
object Downloads {
    /** Returns where the copy went ("Download/Name.apk") or throws with a readable message. */
    fun saveApk(ctx: Context, apk: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("the Downloads folder is not available")
        try {
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("cannot write to Downloads")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)       // do not leave a half-written file behind
            throw e
        }
        return Environment.DIRECTORY_DOWNLOADS + "/" + apk.name
    }
}
