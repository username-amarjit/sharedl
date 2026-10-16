package com.example.sharedl.download

import android.net.Uri

/**
 * The only way a URL gets into the app. Shared text usually looks like
 * "Check this out! https://youtu.be/abc?si=xyz", so we pull the first https link out of it and
 * validate it against an allowlist before it's ever passed to yt-dlp.
 */
object LinkExtractor {

    private const val MAX_LENGTH = 2048
    private val HTTPS_URL = Regex("""https://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}>"

    fun extract(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val match = HTTPS_URL.find(text)?.value ?: return null
        return validate(match.trimEnd { it in TRAILING_PUNCTUATION })
    }

    /**
     * https only (so the value can never start with "-" and be read as a yt-dlp option),
     * a real host, no embedded user:password, and a sane length.
     */
    fun validate(candidate: String): String? {
        if (candidate.isEmpty() || candidate.length > MAX_LENGTH) return null
        if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = Uri.parse(candidate)
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return candidate
    }
}
