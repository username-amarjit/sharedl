package com.example.sharedl.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.sharedl.BuildConfig
import com.example.sharedl.R
import com.example.sharedl.notify.Notifications
import com.example.sharedl.settings.Settings
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

/**
 * One download, start to finish:
 * probe the link -> pick MP4 or audio -> run yt-dlp into a private temp folder -> copy to shared storage.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val notificationId = id.hashCode()
    private val resultNotificationId = notificationId + 1
    private val processId = id.toString()
    private val probeProcessId = "$processId-probe"

    @Volatile
    private var title: String? = null
    private var lastReportAt = 0L

    override suspend fun doWork(): Result {
        // This input comes from our own enqueue(), but check it again anyway:
        // nothing unvalidated is ever handed to yt-dlp.
        val url = inputData.getString(Downloads.KEY_URL)?.let(LinkExtractor::validate)
            ?: return Result.failure(
                workDataOf(Downloads.KEY_ERROR to applicationContext.getString(R.string.error_bad_link))
            )
        val mode = DownloadMode.entries.firstOrNull { it.name == inputData.getString(Downloads.KEY_MODE) }
            ?: DownloadMode.AUTO
        val settings = Settings(applicationContext)

        promoteToForeground()

        val workDir = File(applicationContext.cacheDir, "downloads/$processId")
        var asAudio = mode == DownloadMode.AUDIO
        return try {
            workDir.mkdirs()
            YtDlp.ensureInitialized(applicationContext)

            val probe = runInterruptible(Dispatchers.IO) {
                YtDlp.probe(applicationContext, url, asAudio, settings, probeProcessId)
            }
            title = probe.title
            asAudio = asAudio || probe.audioOnly
            // In AUTO mode the probe sized the video formats. For a link with no video,
            // that's the same audio file the download will fetch, so the size still holds.
            val tracker = ProgressTracker(expectedTotalBytes = probe.totalBytes)
            publish(tracker.snapshot(), force = true)

            val request = YtDlp.downloadRequest(applicationContext, url, asAudio, settings, workDir)
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(
                    request,
                    processId = processId,
                    callback = { percent, _, line -> onOutput(tracker, line, percent) },
                )
            }

            val files = YtDlp.outputFiles(workDir)
            if (files.isEmpty()) throw IOException("yt-dlp finished without writing a file")
            saveAndFinish(files, asAudio, partialDetails = null)
        } catch (e: Exception) {
            stopYtDlp()
            if (e is CancellationException) throw e
            if (isStopped) throw CancellationException("Download cancelled")

            if (BuildConfig.DEBUG) Log.w(TAG, "Download failed", e)
            val details = ErrorMessages.details(e, workDir)

            // yt-dlp sometimes fails in a finishing step (adding metadata or album art, converting)
            // after the media itself downloaded fine. Keep that file instead of throwing it away.
            val rescued = if (ErrorMessages.isPostProcessingError(e)) YtDlp.finishedFiles(workDir) else emptyList()
            if (rescued.isNotEmpty()) {
                try {
                    return saveAndFinish(rescued, asAudio, partialDetails = details)
                } catch (saveError: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Couldn't save the rescued file", saveError)
                }
            }

            val message = ErrorMessages.forUser(applicationContext, e)
            Notifications.showFailed(applicationContext, resultNotificationId, title, message)
            Result.failure(
                workDataOf(
                    Downloads.KEY_TITLE to title,
                    Downloads.KEY_ERROR to message,
                    Downloads.KEY_ERROR_DETAILS to details,
                )
            )
        } finally {
            workDir.deleteRecursively()
            // Only matters if we couldn't become a foreground service and posted progress ourselves.
            NotificationManagerCompat.from(applicationContext).cancel(notificationId)
        }
    }

    /** Copies the files to shared storage. [partialDetails] is set when a finishing step failed. */
    private fun saveAndFinish(files: List<File>, asAudio: Boolean, partialDetails: String?): Result {
        publish(ProgressSnapshot(Phase.SAVING), force = true)
        val saved = files.map { MediaStoreSaver.save(applicationContext, it, asAudio) }
        val partial = partialDetails != null

        Notifications.showSaved(applicationContext, resultNotificationId, title, saved, partial)
        val first = saved.first()
        return Result.success(
            workDataOf(
                Downloads.KEY_TITLE to title,
                Downloads.KEY_URI to first.uri.toString(),
                Downloads.KEY_MIME to first.mimeType,
                Downloads.KEY_FOLDER to first.folder,
                Downloads.KEY_COUNT to saved.size,
                Downloads.KEY_SIZE to saved.sumOf { it.sizeBytes },
                Downloads.KEY_PARTIAL to partial,
                Downloads.KEY_LIMITED to !YtDlp.canMerge(applicationContext),
                Downloads.KEY_ERROR_DETAILS to partialDetails,
            )
        )
    }

    private suspend fun promoteToForeground() {
        try {
            val notification = Notifications.progress(
                applicationContext, id, title = null, snapshot = ProgressSnapshot(Phase.PREPARING),
            )
            setForeground(ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android 12+ won't start a foreground service while the app is in the background, for
            // example when this download was waiting for a network and starts later. It still runs as
            // ordinary background work; it just loses the guarantee that Android won't stop it.
            if (BuildConfig.DEBUG) Log.w(TAG, "Couldn't run as a foreground service", e)
        }
    }

    /** Called from yt-dlp's output thread for every line it prints. */
    private fun onOutput(tracker: ProgressTracker, line: String, libraryPercent: Float) {
        val phaseChanged = tracker.onLine(line, libraryPercent)
        publish(tracker.snapshot(), force = phaseChanged)
    }

    /** Throttled so speed updates stay readable and we stay under Android's notification rate limit. */
    @Synchronized
    private fun publish(snapshot: ProgressSnapshot, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReportAt < REPORT_INTERVAL_MS) return
        lastReportAt = now

        setProgressAsync(snapshot.toWorkData(title))
        Notifications.updateProgress(applicationContext, notificationId, id, title, snapshot)
    }

    private fun stopYtDlp() {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        runCatching { YoutubeDL.getInstance().destroyProcessById(probeProcessId) }
    }

    private companion object {
        const val TAG = "DownloadWorker"
        const val REPORT_INTERVAL_MS = 1000L
    }
}
