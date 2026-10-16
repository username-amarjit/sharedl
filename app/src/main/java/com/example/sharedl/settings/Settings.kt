package com.example.sharedl.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.example.sharedl.R

/**
 * yt-dlp format-sort strings. H.264 video + AAC audio in an MP4 plays on every Android phone, so
 * it's preferred at each resolution. "vcodec:h264" means "the best codec no better than H.264",
 * which still lets Best pick VP9/AV1 when that's the only way to get the higher resolution.
 */
enum class VideoQuality(val formatSort: String, @StringRes val label: Int) {
    P720("res:720,vcodec:h264,acodec:aac", R.string.quality_720),
    P1080("res:1080,vcodec:h264,acodec:aac", R.string.quality_1080),
    BEST("res,vcodec:h264,acodec:aac", R.string.quality_best),
}

enum class AudioFormat(val extension: String, @StringRes val label: Int) {
    MP3("mp3", R.string.format_mp3),
    M4A("m4a", R.string.format_m4a),
}

/** Plain SharedPreferences is fine here: these are display preferences, nothing secret. */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var videoQuality: VideoQuality
        get() = prefs.getString(KEY_VIDEO_QUALITY, null).toEnumOr(VideoQuality.P1080)
        set(value) = prefs.edit { putString(KEY_VIDEO_QUALITY, value.name) }

    var audioFormat: AudioFormat
        get() = prefs.getString(KEY_AUDIO_FORMAT, null).toEnumOr(AudioFormat.MP3)
        set(value) = prefs.edit { putString(KEY_AUDIO_FORMAT, value.name) }

    var nightlyUpdates: Boolean
        get() = prefs.getBoolean(KEY_NIGHTLY, false)
        set(value) = prefs.edit { putBoolean(KEY_NIGHTLY, value) }

    private companion object {
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_AUDIO_FORMAT = "audio_format"
        const val KEY_NIGHTLY = "nightly_updates"
    }
}

// Stored values are checked on read, so an unknown or corrupted value falls back to the default.
private inline fun <reified T : Enum<T>> String?.toEnumOr(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
