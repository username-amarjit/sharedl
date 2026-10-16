package com.example.sharedl.download

import android.content.Context
import com.example.sharedl.R
import java.util.Locale

/**
 * Turns yt-dlp's error output into one plain sentence that says what happened and what to do.
 * The raw error (which can contain URLs and file paths) is only ever logged in debug builds.
 */
internal object ErrorMessages {

    private val DRM = Regex("""\bdrm\b""")

    fun forUser(context: Context, error: Throwable): String {
        val text = generateSequence(error) { it.cause }
            .take(5)
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        val message = when {
            "no space left" in text -> R.string.error_storage
            error is SaveException -> R.string.error_save
            DRM.containsMatchIn(text) -> R.string.error_drm
            "unsupported url" in text -> R.string.error_unsupported
            "not a bot" in text -> R.string.error_bot_check
            "sign in" in text || "log in" in text || "login" in text || "private video" in text -> R.string.error_login
            "http error 403" in text || "http error 429" in text -> R.string.error_blocked
            "unable to resolve" in text || "timed out" in text || "network is unreachable" in text -> R.string.error_network
            else -> R.string.error_generic
        }
        return context.getString(message)
    }
}
