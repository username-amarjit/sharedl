package com.example.sharedl.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.Locale

class SaveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Copies a finished download from the app's private cache into shared storage, so it shows up
 * in your gallery and music apps. MediaStore needs no storage permission on Android 10+.
 */
object MediaStoreSaver {

    private const val APP_FOLDER = "ShareDL"
    private const val BUFFER_SIZE = 1 shl 16

    private val KNOWN_TYPES = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "opus" to "audio/ogg",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
    )

    data class Saved(val uri: Uri, val mimeType: String, val folder: String)

    fun save(context: Context, file: File): Saved {
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = KNOWN_TYPES[ext]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"

        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, folder) = when {
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(volume) to "${Environment.DIRECTORY_MUSIC}/$APP_FOLDER"
            mime.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(volume) to "${Environment.DIRECTORY_MOVIES}/$APP_FOLDER"
            else ->
                MediaStore.Downloads.getContentUri(volume) to "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER"
        }

        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until the copy finishes
        }
        val uri = resolver.insert(collection, pending)
            ?: throw SaveException("MediaStore refused to create the file")

        try {
            val sink = resolver.openOutputStream(uri) ?: throw SaveException("Couldn't open the new file")
            sink.use { out -> file.inputStream().use { it.copyTo(out, BUFFER_SIZE) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // don't leave a half-written file behind
            throw e as? SaveException ?: SaveException("Copy to shared storage failed", e)
        }
        return Saved(uri, mime, folder)
    }
}
