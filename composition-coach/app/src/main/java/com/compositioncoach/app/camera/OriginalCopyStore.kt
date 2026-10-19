package com.compositioncoach.app.camera

import android.graphics.Bitmap
import java.io.File

/**
 * Private review-time copies: photos as they were before the app's own corrections (auto-level), so the review
 * screen's Compare chip has an original to show, and the "Portrait" look's preview ([savePreview]), which only
 * becomes a gallery photo when the user taps "Save as copy". They live in `cacheDir/originals`, never in the
 * gallery, and exist only while their photo is under review: deleted on Keep/Retake (or when a newer capture
 * replaces the review), and purged on the next start in case the process died mid-review. Plain `java.io`, so
 * the lifecycle is unit-tested on the JVM (`OriginalCopyStoreTest`). Callers run it off the main thread.
 */
class OriginalCopyStore(cacheDir: File) {

    private val dir = File(cacheDir, DIR_NAME)

    /** Writes [bytes] to a new copy and returns it, or null if the cache could not take it. */
    fun save(bytes: ByteArray): File? = runCatching {
        dir.mkdirs()
        File.createTempFile(PREFIX, ".jpg", dir).apply { writeBytes(bytes) }
    }.getOrNull()

    /** Encodes [bitmap] (not recycled) as a JPEG preview copy and returns it, or null on failure. */
    fun savePreview(bitmap: Bitmap): File? = runCatching {
        dir.mkdirs()
        val file = File.createTempFile(PREVIEW_PREFIX, ".jpg", dir)
        val ok = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, CaptureQuality.JPEG_QUALITY, it) }
        if (!ok) {
            file.delete()
            null
        } else {
            file
        }
    }.getOrNull()

    /** Deletes the copy at [path]. Refuses (false) anything that is not one of this store's own files. */
    fun delete(path: String?): Boolean {
        val file = path?.let(::File) ?: return false
        if (!owns(file)) return false
        return file.delete()
    }

    /** Deletes every copy; returns how many were removed. */
    fun purge(): Int = dir.listFiles()?.count { it.isFile && isOwnName(it.name) && it.delete() } ?: 0

    /** Whether [file] is directly inside this store's directory (and so is safe to delete). */
    fun owns(file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == dir.canonicalFile && isOwnName(file.name)
    }.getOrDefault(false)

    private fun isOwnName(name: String) = name.startsWith(PREFIX) || name.startsWith(PREVIEW_PREFIX)

    private companion object {
        const val DIR_NAME = "originals"
        const val PREFIX = "cc_original_"
        const val PREVIEW_PREFIX = "cc_preview_"
    }
}
