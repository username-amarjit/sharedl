package com.example.sharedl.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import com.example.sharedl.R
import com.example.sharedl.download.DownloadMode
import com.example.sharedl.download.Downloads

/**
 * Invisible share-sheet target: grab the link, queue the download, close.
 * Uses Theme.NoDisplay, which requires finish() before onResume, so all work happens in onCreate.
 */
abstract class BaseShareActivity(private val mode: DownloadMode) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
        finish()
    }

    private fun handleShare() {
        val incoming = intent ?: return
        if (incoming.action != Intent.ACTION_SEND) return

        // Most apps put the link in EXTRA_TEXT, often with extra words around it.
        // A few only fill in ClipData, so fall back to that.
        val shared: CharSequence? = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text

        val queued = Downloads.enqueueFromText(applicationContext, shared, mode)
        toast(
            when {
                !queued -> R.string.toast_no_link
                mode == DownloadMode.AUDIO -> R.string.toast_started_audio
                else -> R.string.toast_started
            }
        )
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}

/** "Download" in the share sheet: MP4, or audio automatically if the link has no video. */
class ShareAutoActivity : BaseShareActivity(DownloadMode.AUTO)

/** "Download audio" in the share sheet: always audio (MP3 or M4A, per settings). */
class ShareAudioActivity : BaseShareActivity(DownloadMode.AUDIO)
