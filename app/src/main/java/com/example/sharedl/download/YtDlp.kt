package com.example.sharedl.download

import android.content.Context
import android.util.Log
import com.example.sharedl.BuildConfig
import com.example.sharedl.settings.AudioFormat
import com.example.sharedl.settings.Settings
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale

/**
 * Everything that talks to yt-dlp lives here. youtubedl-android passes options to the process as a
 * list of arguments (no shell), so nothing here can be turned into shell injection, and every URL
 * has already been through LinkExtractor.
 */
object YtDlp {

    private const val TAG = "YtDlp"
    private const val MAX_TITLE = 200

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "weba")

    // Files yt-dlp may leave next to the real output. These are never copied to shared storage.
    private val SCRATCH_EXTENSIONS =
        setOf("part", "ytdl", "temp", "tmp", "jpg", "jpeg", "png", "webp", "json", "vtt", "srt")

    // youtubedl-android 0.18.1+ ships QuickJS as a native library. yt-dlp only looks for Deno on its
    // own, so we point it at the bundled binary explicitly.
    private val QUICKJS_LIBRARY = Regex("""lib(qjs|quickjs)[\w.-]*\.so""")

    @Volatile
    private var initialized = false
    private val initLock = Any()

    /** Unpacks Python, yt-dlp and FFmpeg on first run. Safe to call from any thread, any number of times. */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
        }
    }

    data class Probe(val title: String?, val audioOnly: Boolean)

    /**
     * Asks yt-dlp about the link without downloading it: its title, and whether the best
     * available format has a video track. That's how AUTO decides between MP4 and audio.
     */
    fun probe(context: Context, url: String, processId: String): Probe {
        val request = YoutubeDLRequest(url).apply {
            applyBaseOptions(context)
            addOption("--playlist-items", "1")
            addOption("--no-warnings")
            addOption("--print", "%(vcodec)s\t%(ext)s\t%(title)s")
        }
        val output = YoutubeDL.getInstance().execute(request, processId = processId).out
        val line = output.lineSequence().lastOrNull { it.isNotBlank() }
            ?: return Probe(title = null, audioOnly = false)

        // Title goes last, so a tab inside a title can't shift the other fields.
        val fields = line.split('\t', limit = 3)
        val vcodec = fields.getOrNull(0)?.trim()?.lowercase(Locale.ROOT)
        val ext = fields.getOrNull(1)?.trim()?.lowercase(Locale.ROOT)
        val title = fields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }?.take(MAX_TITLE)

        val noVideoTrack = vcodec == "none" || (vcodec == "na" && ext in AUDIO_EXTENSIONS)
        return Probe(title = title, audioOnly = noVideoTrack)
    }

    fun downloadRequest(
        context: Context,
        url: String,
        asAudio: Boolean,
        settings: Settings,
        outDir: File,
    ): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        applyBaseOptions(context)
        addOption("-o", File(outDir, "%(title).150B.%(ext)s").absolutePath)
        addOption("--windows-filenames")          // drops characters SD cards and some players choke on
        addOption("--no-mtime")                   // file date = when you downloaded it, so it sorts first
        addOption("--embed-metadata")
        addOption("--concurrent-fragments", "4")  // faster on sites that stream in chunks

        if (asAudio) {
            val format = settings.audioFormat
            addOption("-f", "ba/b")
            addOption("--extract-audio")
            addOption("--audio-format", format.extension)
            addOption("--audio-quality", "0")
            // For M4A, prefer the AAC stream so the audio is copied as-is instead of re-encoded.
            if (format == AudioFormat.M4A) addOption("-S", "acodec:aac")
            addOption("--embed-thumbnail")        // album art
            addOption("--convert-thumbnails", "jpg")
        } else {
            addOption("-f", "bv*+ba/b")
            addOption("-S", settings.videoQuality.formatSort)
            addOption("--merge-output-format", "mp4")
            addOption("--remux-video", "mp4")
        }
    }

    /** The finished media files in [dir], ignoring yt-dlp's scratch files. */
    fun outputFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase(Locale.ROOT) !in SCRATCH_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()

    fun version(context: Context): String? = try {
        ensureInitialized(context)
        val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        YoutubeDL.getInstance().execute(request).out.trim().ifEmpty { null }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w(TAG, "Couldn't read the yt-dlp version", e)
        null
    }

    enum class UpdateResult { UPDATED, ALREADY_LATEST, FAILED }

    /** Downloads the newest yt-dlp from its official GitHub releases. Blocking; call off the main thread. */
    fun update(context: Context, nightly: Boolean): UpdateResult = try {
        ensureInitialized(context)
        val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        when (YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, channel)) {
            YoutubeDL.UpdateStatus.DONE -> UpdateResult.UPDATED
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> UpdateResult.ALREADY_LATEST
            else -> UpdateResult.FAILED
        }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w(TAG, "yt-dlp update failed", e)
        UpdateResult.FAILED
    }

    private fun YoutubeDLRequest.applyBaseOptions(context: Context) {
        // Ignore any config file on the device: only the options written here ever apply,
        // so nothing can sneak in something like --exec.
        addOption("--ignore-config")
        // A "watch?v=...&list=..." link means this one video, not the whole playlist.
        addOption("--no-playlist")
        addOption("--socket-timeout", "30")
        addOption("--retries", "5")

        quickJs(context)?.let { addOption("--js-runtimes", "quickjs:${it.absolutePath}") }
        // YouTube also needs yt-dlp's challenge-solver scripts (EJS). If this yt-dlp build doesn't
        // include them, let it fetch the official copy from yt-dlp's GitHub releases over HTTPS.
        // Delete this line if you never want yt-dlp to fetch code at runtime (YouTube may then fail).
        addOption("--remote-components", "ejs:github")
    }

    private fun quickJs(context: Context): File? {
        val libDir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(libDir).listFiles()?.firstOrNull { it.isFile && QUICKJS_LIBRARY.matches(it.name) }
    }
}
