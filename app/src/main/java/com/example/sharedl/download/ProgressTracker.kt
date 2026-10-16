package com.example.sharedl.download

import androidx.work.Data
import androidx.work.workDataOf
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Phase { PREPARING, DOWNLOADING, PROCESSING, SAVING }

/** One moment of a download. -1 means "not known (yet)". */
data class ProgressSnapshot(
    val phase: Phase,
    val percent: Int = -1,
    val downloadedBytes: Long = -1,
    val totalBytes: Long = -1,
    val speedBytesPerSecond: Long = -1,
    val etaSeconds: Long = -1,
    val item: Int = 0,
    val itemCount: Int = 0,
)

/**
 * Turns yt-dlp's console output into one overall progress figure.
 *
 * yt-dlp reports each stream on its own: a typical video downloads the video track 0–100% and then
 * the audio track 0–100%. This adds them up into a single number for the whole file, using the
 * total size from the probe when yt-dlp knew it up front, so the bar never jumps back to 0.
 */
class ProgressTracker(private val expectedTotalBytes: Long?) {

    private var phase = Phase.PREPARING
    private var item = 0
    private var itemCount = 0
    private var finishedBytes = 0L      // streams of the current item that are fully downloaded
    private var streamTotal = -1L
    private var streamDone = -1L
    private var streamCounted = false
    private var speed = -1L
    private var eta = -1L
    private var libraryPercent = -1f

    /** Reads one line of output. Returns true when the phase changed, which is worth showing immediately. */
    @Synchronized
    fun onLine(line: String, libraryPercent: Float): Boolean {
        val before = phase
        this.libraryPercent = libraryPercent

        val itemMatch = ITEM.find(line)
        val percentMatch = PERCENT.find(line)
        val bytesMatch = BYTES_ONLY.find(line)
        when {
            itemMatch != null -> { // next entry of a playlist
                item = itemMatch.groupValues[1].toIntOrNull() ?: 0
                itemCount = itemMatch.groupValues[2].toIntOrNull() ?: 0
                finishedBytes = 0
                resetStream()
            }
            DESTINATION.containsMatchIn(line) -> { // a new stream (video, then audio) starts
                finishStream()
                resetStream()
                phase = Phase.DOWNLOADING
            }
            percentMatch != null -> {
                phase = Phase.DOWNLOADING
                val percent = percentMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                TOTAL.find(line)?.let { match ->
                    val total = parseSize(match)
                    if (total > 0) {
                        streamTotal = total
                        streamDone = (total * percent / 100).roundToLong()
                    }
                }
                speed = SPEED.find(line)?.let { parseSize(it) } ?: -1
                eta = ETA.find(line)?.let { parseEta(it) } ?: -1
                if (percent >= 100.0) finishStream()
            }
            bytesMatch != null -> { // live streams and some sites don't report a total
                phase = Phase.DOWNLOADING
                streamDone = parseSize(bytesMatch)
                speed = SPEED.find(line)?.let { parseSize(it) } ?: -1
                eta = -1
            }
            POSTPROCESS.containsMatchIn(line) -> { // merging, converting, adding album art
                finishStream()
                phase = Phase.PROCESSING
            }
        }
        return phase != before
    }

    @Synchronized
    fun snapshot(): ProgressSnapshot {
        val current = if (streamCounted) 0L else max(streamDone, 0L)
        val downloaded = if (finishedBytes > 0 || streamDone >= 0) finishedBytes + current else -1L
        // The probe only measured the first entry, so don't use it for playlists.
        val expected = expectedTotalBytes?.takeIf { it > 0 && itemCount <= 1 }
        val liveTotal = if (streamTotal > 0) finishedBytes + (if (streamCounted) 0L else streamTotal) else -1L
        val total = if (expected != null) max(expected, downloaded) else liveTotal

        val downloading = phase == Phase.DOWNLOADING
        val percent = when {
            !downloading -> -1
            total > 0 && downloaded >= 0 -> (downloaded * 100 / total).toInt().coerceIn(0, 100)
            finishedBytes == 0L && libraryPercent >= 0f -> libraryPercent.toInt().coerceIn(0, 100)
            else -> -1
        }
        return ProgressSnapshot(
            phase = phase,
            percent = percent,
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSecond = if (downloading) speed else -1,
            etaSeconds = if (downloading) eta else -1,
            item = item,
            itemCount = itemCount,
        )
    }

    private fun finishStream() {
        if (!streamCounted && (streamTotal > 0 || streamDone > 0)) {
            finishedBytes += if (streamTotal > 0) streamTotal else streamDone
            streamCounted = true
        }
    }

    private fun resetStream() {
        streamTotal = -1
        streamDone = -1
        streamCounted = false
    }

    private companion object {
        val ITEM = Regex("""^\[download\] Downloading (?:item|video) (\d+) of (\d+)""")
        val DESTINATION = Regex("""^\[download\] Destination: """)
        val PERCENT = Regex("""^\[download\]\s+(\d+(?:\.\d+)?)%""")
        val TOTAL = Regex("""\bof\s+~?\s*(\d+(?:\.\d+)?)\s*([KMGTP]?i?B)\b""")
        val SPEED = Regex("""\bat\s+(\d+(?:\.\d+)?)\s*([KMGTP]?i?B)/s""")
        val ETA = Regex("""\bETA\s+(?:(\d+):)?(\d+):(\d+)""")
        val BYTES_ONLY = Regex("""^\[download\]\s+(\d+(?:\.\d+)?)\s*([KMGTP]?i?B)\s+at\b""")
        val POSTPROCESS = Regex(
            """^\[(Merger|ExtractAudio|VideoRemuxer|VideoConvertor|EmbedThumbnail|Metadata|ThumbnailsConvertor|MoveFiles|Fixup\w*|FFmpeg\w*)\]"""
        )

        /** "45.67MiB" -> bytes. yt-dlp uses binary units (KiB, MiB); plain KB/MB are decimal. */
        fun parseSize(match: MatchResult): Long {
            val value = match.groupValues[1].toDoubleOrNull() ?: return -1
            val unit = match.groupValues[2]
            val base = if ('i' in unit) 1024.0 else 1000.0
            val power = when (unit.firstOrNull()?.uppercaseChar()) {
                'K' -> 1
                'M' -> 2
                'G' -> 3
                'T' -> 4
                'P' -> 5
                else -> 0
            }
            return (value * base.pow(power)).roundToLong()
        }

        fun parseEta(match: MatchResult): Long {
            val hours = match.groupValues[1].toLongOrNull() ?: 0
            val minutes = match.groupValues[2].toLongOrNull() ?: 0
            val seconds = match.groupValues[3].toLongOrNull() ?: 0
            return hours * 3600 + minutes * 60 + seconds
        }
    }
}

fun ProgressSnapshot.toWorkData(title: String?): Data = workDataOf(
    Downloads.KEY_TITLE to title,
    Downloads.KEY_PHASE to phase.name,
    Downloads.KEY_PROGRESS to percent,
    Downloads.KEY_DOWNLOADED to downloadedBytes,
    Downloads.KEY_TOTAL to totalBytes,
    Downloads.KEY_SPEED to speedBytesPerSecond,
    Downloads.KEY_ETA to etaSeconds,
    Downloads.KEY_ITEM to item,
    Downloads.KEY_ITEM_COUNT to itemCount,
)

fun Data.toProgressSnapshot(): ProgressSnapshot = ProgressSnapshot(
    phase = Phase.entries.firstOrNull { it.name == getString(Downloads.KEY_PHASE) } ?: Phase.PREPARING,
    percent = getInt(Downloads.KEY_PROGRESS, -1),
    downloadedBytes = getLong(Downloads.KEY_DOWNLOADED, -1),
    totalBytes = getLong(Downloads.KEY_TOTAL, -1),
    speedBytesPerSecond = getLong(Downloads.KEY_SPEED, -1),
    etaSeconds = getLong(Downloads.KEY_ETA, -1),
    item = getInt(Downloads.KEY_ITEM, 0),
    itemCount = getInt(Downloads.KEY_ITEM_COUNT, 0),
)
