package com.example.sharedl.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.example.sharedl.MainActivity
import com.example.sharedl.R
import com.example.sharedl.download.MediaStoreSaver
import java.util.Locale
import java.util.UUID

object Notifications {

    private const val CHANNEL_PROGRESS = "downloads"
    private const val CHANNEL_RESULTS = "results"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    context.getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW, // silent progress bar
                ),
                NotificationChannel(
                    CHANNEL_RESULTS,
                    context.getString(R.string.channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun progress(context: Context, workId: UUID, title: String?, percent: Int, etaSeconds: Long): Notification {
        val text = when {
            percent < 0 -> context.getString(R.string.status_preparing)
            etaSeconds > 0 -> context.getString(R.string.status_percent_eta, percent, formatEta(etaSeconds))
            else -> context.getString(R.string.status_percent, percent)
        }
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title ?: context.getString(R.string.notification_title_pending))
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_cancel), cancel)
            .build()
    }

    fun updateProgress(context: Context, id: Int, workId: UUID, title: String?, percent: Int, etaSeconds: Long) {
        post(context, id, progress(context, workId, title, percent, etaSeconds))
    }

    fun showSaved(context: Context, id: Int, title: String?, saved: List<MediaStoreSaver.Saved>) {
        val first = saved.firstOrNull() ?: return
        val text = if (saved.size == 1) {
            context.getString(R.string.saved_to, first.folder)
        } else {
            context.getString(R.string.saved_many_to, saved.size, first.folder)
        }
        // One file: tapping opens it. A whole playlist: tapping opens the app.
        val tapAction = if (saved.size == 1) openFile(context, id, first) else openApp(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title ?: context.getString(R.string.notification_title_done))
            .setContentText(text)
            .setContentIntent(tapAction)
            .setAutoCancel(true)
            .build()
        post(context, id, notification)
    }

    fun showFailed(context: Context, id: Int, title: String?, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title ?: context.getString(R.string.notification_title_failed))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, id, notification)
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openFile(context: Context, requestCode: Int, saved: MediaStoreSaver.Saved): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(saved.uri, saved.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, requestCode, view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission") // checked by canPost()
    private fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun formatEta(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
