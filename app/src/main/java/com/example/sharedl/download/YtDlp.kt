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

    /** [totalBytes] is yt-dlp's size estimate for everything it will download, when the site reports it. */
    data class Probe(val title: String?, val audioOnly: Boolean, val totalBytes: Long?)

    /**
     * Asks yt-dlp about the link without downloading it: its title, its size, and whether the
     * chosen format has a video track. That's how AUTO decides between MP4 and audio.
     * It uses the same format choice as the real download, so the size matches what you'll get.
     */
    fun probe(context: Context, url: String, asAudio: Boolean, settings: Settings, processId: String): Probe {
        val request = YoutubeDLRequest(url).apply {
            applyBaseOptions(context)
            applyFormatOptions(asAudio, settings, canMerge(context))
            addOption("--playlist-items", "1")
            addOption("--no-warnings")
            // Title goes last, so a tab inside a title can't shift the other fields.
            addOption("--print", "%(vcodec)s\t%(ext)s\t%(filesize,filesize_approx)s\t%(title)s")
        }
        val output = YoutubeDL.getInstance().execute(request, processId = processId).out
        val line = output.lineSequence().lastOrNull { it.isNotBlank() }
            ?: return Probe(title = null, audioOnly = false, totalBytes = null)

        val fields = line.split('\t', limit = 4)
        val vcodec = fields.getOrNull(0)?.trim()?.lowercase(Locale.ROOT)
        val ext = fields.getOrNull(1)?.trim()?.lowercase(Locale.ROOT)
        val size = fields.getOrNull(2)?.trim()?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
        val title = fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }?.take(MAX_TITLE)

        val noVideoTrack = vcodec == "none" || (vcodec == "na" && ext in AUDIO_EXTENSIONS)
        return Probe(title = title, audioOnly = noVideoTrack, totalBytes = size)
    }

    fun downloadRequest(
        context: Context,
        url: String,
        asAudio: Boolean,
        settings: Settings,
        outDir: File,
    ): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        val canMerge = canMerge(context)
        applyBaseOptions(context)
        addOption("-o", File(outDir, "%(title).150B.%(ext)s").absolutePath)
        addOption("--windows-filenames")          // drops characters SD cards and some players choke on
        addOption("--no-mtime")                   // file date = when you downloaded it, so it sorts first
        addOption("--concurrent-fragments", "4")  // faster on sites that stream in chunks

        applyFormatOptions(asAudio, settings, canMerge)

        // Everything below needs FFmpeg. Without it we keep the file exactly as the site serves it.
        if (!canMerge) return@apply
        addOption("--embed-metadata")
        if (asAudio) {
            addOption("--extract-audio")
            addOption("--audio-format", settings.audioFormat.extension)
            addOption("--audio-quality", "0")
            addOption("--embed-thumbnail")        // album art
            addOption("--convert-thumbnails", "jpg")
        } else {
            addOption("--merge-output-format", "mp4")
            addOption("--remux-video", "mp4")
        }
    }

    /** True when FFmpeg is available, which is what merging, converting and album art need. */
    fun canMerge(context: Context): Boolean = Ffmpeg.canRun(context)

    /** Which streams to pick. Shared by the probe and the download so both agree on the size. */
    private fun YoutubeDLRequest.applyFormatOptions(asAudio: Boolean, settings: Settings, canMerge: Boolean) {
        when {
            asAudio && canMerge -> {
                addOption("-f", "ba/b")
                // For M4A, prefer the AAC stream so the audio is copied as-is instead of re-encoded.
                if (settings.audioFormat == AudioFormat.M4A) addOption("-S", "acodec:aac")
            }
            // No FFmpeg: take an audio file as-is. It can't be converted to MP3.
            asAudio -> addOption("-f", "ba[ext=m4a]/ba")
            canMerge -> {
                addOption("-f", "bv*+ba/b")
                addOption("-S", settings.videoQuality.formatSort)
            }
            // No FFmpeg: only formats that already contain video and audio together (lower quality).
            else -> {
                addOption("-f", "b[ext=mp4]/b")
                addOption("-S", settings.videoQuality.formatSort)
            }
        }
    }

    // "Title.f137.mp4" is one stream before merging; "Title.temp.mp4" is a half-written file from a failed step.
    private val INTERMEDIATE = Regex("""\.(f[\w=+-]+|temp)\.\w+$""")

    /** The finished media files in [dir], ignoring yt-dlp's scratch files. */
    fun outputFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase(Locale.ROOT) !in SCRATCH_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Like [outputFiles], but also skips separate streams that never got merged and half-written
     * files. Used to rescue a download when only a finishing step failed.
     */
    fun finishedFiles(dir: File): List<File> =
        outputFiles(dir).filterNot { INTERMEDIATE.containsMatchIn(it.name) }

    fun version(context: Context): String? = try {
        ensureInitialized(context)
        val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        YoutubeDL.getInstance().execute(request).out.trim().ifEmpty { null }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w(TAG, "Couldn't read the yt-dlp version", e)
        null
    }

    /**
     * yt-dlp's own verbose header: the exact options it received (including any the library adds)
     * and which FFmpeg it found. With no link given, yt-dlp prints the header and then exits with
     * an error, which is all we need here.
     */
    fun debugHeader(context: Context): String {
        val text = try {
            ensureInitialized(context)
            val request = YoutubeDLRequest(emptyList<String>()).apply {
                addOption("--ignore-config")
                addOption("-v")
            }
            val response = YoutubeDL.getInstance().execute(request)
            response.err + "\n" + response.out
        } catch (e: Exception) {
            e.message.orEmpty()
        }
        val keys = listOf("Command-line config", "exe versions", "yt-dlp version", "[debug] Python")
        return text.lines()
            .filter { line -> keys.any { it in line } || "ffmpeg" in line.lowercase(Locale.ROOT) }
            .take(20)
            .joinToString("\n")
            .ifEmpty { "yt-dlp printed nothing about FFmpeg." }
    }

    enum class UpdateResult { UPDATED, ALREADY_LATEST, FAILED }

    /** Downloads the newest yt-dlp from its official GitHub releases. Blocking; call off the main thread. */
    fun update(context: Context, nightly: Boolean): UpdateResult = try {
        ensureInitialized(context)
        val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        when (YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, channel)) {
            YoutubeDL.UpdateStatus.DONE -> {
                // Cached data from the old version may not suit the new one.
                cacheDir(context).deleteRecursively()
                UpdateResult.UPDATED
            }
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
        // Let yt-dlp keep YouTube's player code and solver scripts between runs, so the probe and the
        // download don't each fetch them again. Lives in the app's own cache folder.
        addOption("--cache-dir", cacheDir(context).absolutePath)
        // youtubedl-android adds --ffmpeg-location and --js-runtimes (its bundled QuickJS) itself.

        // YouTube also needs yt-dlp's challenge-solver scripts (EJS). If this yt-dlp build doesn't
        // include them, let it fetch the official copy from yt-dlp's GitHub releases over HTTPS.
        // Delete this line if you never want yt-dlp to fetch code at runtime (YouTube may then fail).
        addOption("--remote-components", "ejs:github")
    }

    private fun cacheDir(context: Context) = File(context.cacheDir, "yt-dlp")

}
