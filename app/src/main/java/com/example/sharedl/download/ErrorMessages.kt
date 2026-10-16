package com.example.sharedl.download

import android.content.Context
import com.example.sharedl.R
import java.io.File
import java.util.Locale

/**
 * Turns yt-dlp's error output into one plain sentence that says what happened and what to do,
 * plus a trimmed copy of the raw error for the Details view.
 */
internal object ErrorMessages {

    private const val MAX_DETAILS = 3000
    private val DRM = Regex("""\bdrm\b""")

    fun forUser(context: Context, error: Throwable): String {
        val everything = fullText(error).lowercase(Locale.ROOT)
        // Classify on yt-dlp's ERROR lines only. Its WARNING lines often mention logins, cookies or
        // DRM even when that isn't what actually went wrong.
        val errors = errorLines(fullText(error).lines()).joinToString("\n").lowercase(Locale.ROOT)
            .ifEmpty { everything }

        val message = when {
            "no space left" in everything -> R.string.error_storage
            error is SaveException -> R.string.error_save
            DRM.containsMatchIn(errors) -> R.string.error_drm
            "unsupported url" in errors -> R.string.error_unsupported
            "not a bot" in errors -> R.string.error_bot_check
            "requested format is not available" in errors -> R.string.error_format
            "ffmpeg not found" in errors || "ffprobe and ffmpeg not found" in errors -> R.string.error_ffmpeg
            "postprocessing" in errors -> R.string.error_processing
            "sign in" in errors || "log in" in errors || "login" in errors || "private video" in errors -> R.string.error_login
            "http error 403" in errors || "http error 429" in errors -> R.string.error_blocked
            "unable to resolve" in errors || "timed out" in errors || "network is unreachable" in errors -> R.string.error_network
            else -> R.string.error_generic
        }
        return context.getString(message)
    }

    /** yt-dlp failed in a finishing step (merging, converting, adding metadata or album art). */
    fun isPostProcessingError(error: Throwable): Boolean =
        errorLines(fullText(error).lines()).any { "postprocessing" in it.lowercase(Locale.ROOT) }

    /**
     * The raw error, trimmed for reading: yt-dlp's ERROR lines first, then the last lines of its
     * output for context. The app's private temp path is shortened to "…" to keep it readable.
     */
    fun details(error: Throwable, workDir: File): String {
        val lines = fullText(error)
            .replace(workDir.absolutePath, "…")
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return error.javaClass.simpleName
        return (errorLines(lines) + lines.takeLast(15)).distinct().joinToString("\n").take(MAX_DETAILS)
    }

    private fun fullText(error: Throwable): String =
        generateSequence(error) { it.cause }.take(5).mapNotNull { it.message }.joinToString("\n")

    private fun errorLines(lines: List<String>): List<String> =
        lines.filter { it.trimStart().startsWith("ERROR:") }
}
