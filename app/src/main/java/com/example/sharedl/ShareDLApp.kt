package com.example.sharedl

import android.app.Application
import android.util.Log
import com.example.sharedl.download.YtDlp
import com.example.sharedl.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShareDLApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        // The first launch unpacks Python, yt-dlp and FFmpeg, which takes a few seconds.
        // Start that now, off the main thread, so the first download doesn't have to wait as long.
        appScope.launch {
            try {
                YtDlp.ensureInitialized(this@ShareDLApp)
            } catch (e: Exception) {
                // DownloadWorker retries initialization and shows a readable error if it still fails.
                if (BuildConfig.DEBUG) Log.w("ShareDLApp", "yt-dlp warm-up failed", e)
            }
        }
    }
}
