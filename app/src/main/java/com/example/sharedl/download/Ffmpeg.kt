package com.example.sharedl.download

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks whether the FFmpeg that youtubedl-android bundles can actually run on this phone.
 *
 * Finding the file isn't enough. On phones that use 16 KB memory pages (Android 15+ devices and
 * emulators can), Android refuses to load libraries built for 4 KB pages, and the bundled FFmpeg
 * currently includes some. So we run "ffmpeg -version" once and use the real result: when it
 * fails, downloads switch to formats that need no FFmpeg instead of failing at the end.
 */
object Ffmpeg {

    private const val FFMPEG_LIB = "libffmpeg.so"
    private const val PAGE_SIZE_ERROR = "cannot be smaller than system page size"

    enum class State { READY, MISSING, PAGE_SIZE, BROKEN }

    data class Status(val state: State, val abis: String, val pageSizeKb: Long)

    private class RunResult(val exitCode: Int?, val output: String)

    @Volatile
    private var cached: Status? = null

    /** True when FFmpeg starts, which is what merging, converting and album art need. */
    fun canRun(context: Context): Boolean = status(context).state == State.READY

    /** Runs the check once per app launch; later calls reuse the answer. */
    fun status(context: Context): Status {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return computeStatus(context).also { cached = it }
        }
    }

    private fun computeStatus(context: Context): Status {
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        val pageSizeKb = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
        val exe = find(context) ?: return Status(State.MISSING, abis, pageSizeKb)

        // FFmpeg's helper libraries are unpacked during initialization, so make sure that happened.
        runCatching { YtDlp.ensureInitialized(context) }
        val result = run(exe, libraryPath(context))
        val state = when {
            result.exitCode == 0 -> State.READY
            PAGE_SIZE_ERROR in result.output -> State.PAGE_SIZE
            else -> State.BROKEN
        }
        return Status(state, abis, pageSizeKb)
    }

    /** For the Settings "Test FFmpeg" button: the raw results, to paste into a bug report. */
    fun diagnose(context: Context): String = buildString {
        val exe = find(context)
        appendLine("Processor: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Memory page size: ${Os.sysconf(OsConstants._SC_PAGESIZE) / 1024} KB")
        appendLine("FFmpeg file: ${exe?.path ?: "not found"}")
        appendLine()
        appendLine("== ffmpeg -version, with the bundled libraries ==")
        if (exe == null) {
            appendLine("Skipped: no FFmpeg file.")
        } else {
            runCatching { YtDlp.ensureInitialized(context) }
            val libs = libraryPath(context)
            appendLine("LD_LIBRARY_PATH=$libs")
            val result = run(exe, libs)
            appendLine(result.exitCode?.let { "Exit code $it" } ?: "Couldn't start it")
            appendLine(result.output.lines().take(8).joinToString("\n"))
        }
        appendLine()
        appendLine("== What yt-dlp detects ==")
        append(YtDlp.debugHeader(context))
    }

    private fun find(context: Context): File? =
        context.applicationInfo.nativeLibraryDir
            ?.let { File(it, FFMPEG_LIB) }
            ?.takeIf { it.isFile }

    /** Fixed program and arguments, no shell: nothing here comes from user input. */
    private fun run(exe: File, libraryPath: String): RunResult = try {
        val process = ProcessBuilder(exe.path, "-hide_banner", "-version")
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = libraryPath }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(10, TimeUnit.SECONDS)) {
            RunResult(process.exitValue(), output)
        } else {
            process.destroy()
            RunResult(null, "Timed out")
        }
    } catch (e: Exception) {
        RunResult(null, e.message.orEmpty())
    }

    /** The same library folders youtubedl-android gives yt-dlp: its unpacked packages, plus the app's native libraries. */
    private fun libraryPath(context: Context): String {
        val dirs = context.noBackupFilesDir.walkTopDown().maxDepth(6)
            .filter { it.isDirectory && it.name == "lib" && it.parentFile?.name == "usr" }
            .map { it.path }
            .toMutableList()
        context.applicationInfo.nativeLibraryDir?.let { dirs += it }
        return dirs.joinToString(":")
    }
}
