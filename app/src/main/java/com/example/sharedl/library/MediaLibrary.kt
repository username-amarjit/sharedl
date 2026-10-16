package com.example.sharedl.library

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import com.example.sharedl.download.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class MediaFile(
    val id: Long,
    val uri: Uri,
    val title: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
    val mimeType: String,
    val isVideo: Boolean,
)

/**
 * The files ShareDL has saved, read straight from Android's media store, so the list is always
 * what's really on the phone (including files deleted from another app).
 *
 * Without any storage permission Android only shows an app the files it created itself, which is
 * exactly the set we want. One catch: after uninstalling and reinstalling the app, Android no
 * longer counts older files as ours, so they stay on the phone but won't be listed here.
 */
object MediaLibrary {

    private val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val AUDIO: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Emits the current files, then again whenever media on the phone changes. */
    fun observe(context: Context): Flow<List<MediaFile>> {
        val app = context.applicationContext
        return changes(app)
            .onStart { emit(Unit) }
            .conflate() // a burst of changes (one download = several updates) becomes one re-read
            .map { runCatching { query(app) }.getOrDefault(emptyList()) }
            .flowOn(Dispatchers.IO)
    }

    /** Deletes one of our own files. Returns false if Android refused. */
    fun delete(context: Context, file: MediaFile): Boolean = try {
        context.contentResolver.delete(file.uri, null, null) > 0
    } catch (e: SecurityException) {
        false
    }

    private fun changes(context: Context): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun query(context: Context): List<MediaFile> =
        (queryCollection(context, VIDEOS, MediaStoreSaver.VIDEO_FOLDER, isVideo = true) +
            queryCollection(context, AUDIO, MediaStoreSaver.AUDIO_FOLDER, isVideo = false))
            .sortedByDescending { it.dateAddedSeconds }

    private fun queryCollection(context: Context, collection: Uri, folder: String, isVideo: Boolean): List<MediaFile> {
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        // Parameterized selection. MediaStore stores the folder with a trailing slash; match both forms.
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        val args = arrayOf("$folder/", folder)

        val files = mutableListOf<MediaFile>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol).orEmpty()
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
                files += MediaFile(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = title,
                    sizeBytes = cursor.getLong(sizeCol),
                    durationMs = cursor.getLong(durationCol),
                    dateAddedSeconds = cursor.getLong(dateCol),
                    mimeType = cursor.getString(mimeCol) ?: if (isVideo) "video/*" else "audio/*",
                    isVideo = isVideo,
                )
            }
        }
        return files
    }
}
