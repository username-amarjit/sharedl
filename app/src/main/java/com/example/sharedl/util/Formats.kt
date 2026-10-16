package com.example.sharedl.util

import android.content.Context
import android.text.format.Formatter
import com.example.sharedl.R
import com.example.sharedl.download.Phase
import com.example.sharedl.download.ProgressSnapshot
import java.util.Locale

/** Human-readable sizes, times and progress text, shared by the app screens and the notifications. */
object Formats {

    fun size(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

    /** 75 -> "1:15", 3723 -> "1:02:03". */
    fun duration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }

    /** The main line: "45 MB of 120 MB", or what's happening when there are no numbers to show. */
    fun progressHeadline(context: Context, snapshot: ProgressSnapshot): String = when (snapshot.phase) {
        Phase.PREPARING -> context.getString(R.string.status_preparing)
        Phase.PROCESSING -> context.getString(R.string.phase_processing)
        Phase.SAVING -> context.getString(R.string.phase_saving)
        Phase.DOWNLOADING -> when {
            snapshot.downloadedBytes >= 0 && snapshot.totalBytes > 0 -> context.getString(
                R.string.progress_bytes,
                size(context, snapshot.downloadedBytes),
                size(context, snapshot.totalBytes),
            )
            snapshot.downloadedBytes >= 0 -> size(context, snapshot.downloadedBytes)
            else -> context.getString(R.string.status_downloading)
        }
    }

    /** The second line: "2.3 MB/s, 0:31 left, item 3 of 12". Null when there's nothing to add. */
    fun progressDetail(context: Context, snapshot: ProgressSnapshot): String? {
        val parts = buildList {
            if (snapshot.speedBytesPerSecond > 0) {
                add(context.getString(R.string.speed_per_second, size(context, snapshot.speedBytesPerSecond)))
            }
            if (snapshot.etaSeconds > 0) {
                add(context.getString(R.string.eta_left, duration(snapshot.etaSeconds)))
            }
            if (snapshot.itemCount > 1) {
                add(context.getString(R.string.item_of, snapshot.item, snapshot.itemCount))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun savedSummary(context: Context, folder: String, count: Int, sizeBytes: Long): String {
        val hasSize = sizeBytes > 0
        return when {
            count > 1 && hasSize -> context.getString(R.string.saved_many_to_size, count, folder, size(context, sizeBytes))
            count > 1 -> context.getString(R.string.saved_many_to, count, folder)
            hasSize -> context.getString(R.string.saved_to_size, folder, size(context, sizeBytes))
            else -> context.getString(R.string.saved_to, folder)
        }
    }
}
