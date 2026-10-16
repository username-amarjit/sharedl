package com.example.sharedl.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

/** What the user asked for. AUTO saves an MP4, or audio when the link has no video track. */
enum class DownloadMode { AUTO, AUDIO }

// WorkInfo doesn't expose a request's input data, so what the list UI needs rides along as tags.
private const val TAG_URL = "url:"
private const val TAG_MODE = "mode:"
private const val TAG_CREATED = "created:"

object Downloads {
    const val TAG_ALL = "sharedl-download"

    const val KEY_URL = "url"
    const val KEY_MODE = "mode"
    const val KEY_TITLE = "title"
    const val KEY_PROGRESS = "progress"
    const val KEY_PHASE = "phase"
    const val KEY_DOWNLOADED = "downloaded"
    const val KEY_TOTAL = "total"
    const val KEY_SPEED = "speed"
    const val KEY_ETA = "eta"
    const val KEY_ITEM = "item"
    const val KEY_ITEM_COUNT = "item_count"
    const val KEY_URI = "uri"
    const val KEY_MIME = "mime"
    const val KEY_FOLDER = "folder"
    const val KEY_COUNT = "count"
    const val KEY_SIZE = "size"
    const val KEY_ERROR = "error"
    const val KEY_ERROR_DETAILS = "error_details"
    const val KEY_PARTIAL = "partial"
    const val KEY_LIMITED = "limited"

    /** Finds a link in shared or pasted text and queues it. Returns false if there was no valid link. */
    fun enqueueFromText(context: Context, text: CharSequence?, mode: DownloadMode): Boolean {
        val url = LinkExtractor.extract(text) ?: return false
        return enqueue(context, url, mode)
    }

    /**
     * Queues a download. Sharing the same link again while it's still running does nothing,
     * so a double tap in the share sheet doesn't download twice.
     */
    fun enqueue(context: Context, rawUrl: String, mode: DownloadMode): Boolean {
        val url = LinkExtractor.validate(rawUrl) ?: return false
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_URL to url, KEY_MODE to mode.name))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag(TAG_ALL)
            .addTag(TAG_URL + url)
            .addTag(TAG_MODE + mode.name)
            .addTag(TAG_CREATED + System.currentTimeMillis())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("download:${mode.name}:$url", ExistingWorkPolicy.KEEP, request)
        return true
    }
}

fun WorkInfo.sourceUrl(): String? =
    tags.firstOrNull { it.startsWith(TAG_URL) }?.removePrefix(TAG_URL)

fun WorkInfo.mode(): DownloadMode {
    val name = tags.firstOrNull { it.startsWith(TAG_MODE) }?.removePrefix(TAG_MODE)
    return DownloadMode.entries.firstOrNull { it.name == name } ?: DownloadMode.AUTO
}

fun WorkInfo.createdAt(): Long =
    tags.firstOrNull { it.startsWith(TAG_CREATED) }?.removePrefix(TAG_CREATED)?.toLongOrNull() ?: 0L
