package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.ReturnCode
import com.example.domain.EncoderCapabilities
import com.example.domain.FfmpegMath
import com.example.domain.KeyframePointResult
import com.example.domain.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

data class FfmpegStepResult(
    val success: Boolean,
    val cancelled: Boolean,
    val returnCode: Int,
    val logs: List<String>
)

object FfmpegEngine {

    private const val WORK_DIR_PREFIX = "fflab_work_"

    @Volatile
    private var cachedCapabilities: EncoderCapabilities? = null

    @Volatile
    private var nativeInitFailure: Throwable? = null

    @Volatile
    private var logRedirectionConfigured: Boolean = false

    private val previewFallbackMutex = Mutex()

    /**
     * Prevents FFmpegKit from dumping normal stdout/stderr (such as `-h encoder=libx264` probes)
     * into Android Logcat at `E/ffmpeg-kit` error level, while still delivering full logs to
     * `session.allLogsAsString` and per-session callbacks.
     */
    private fun ensureSilentLogcat() {
        if (!logRedirectionConfigured) {
            runCatching {
                FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
                logRedirectionConfigured = true
            }
        }
    }

    /**
     * Formats the full throwable cause chain so root linkage / IO causes are never hidden
     * behind a generic NoClassDefFoundError class name.
     */
    fun describeError(throwable: Throwable): String {
        val root = if (throwable is NoClassDefFoundError && nativeInitFailure != null) {
            nativeInitFailure!!
        } else {
            throwable
        }
        val parts = mutableListOf<String>()
        var current: Throwable? = root
        var depth = 0
        while (current != null && depth < 4) {
            val label = current.javaClass.simpleName
            val msg = current.message?.trim().orEmpty()
            val entry = if (msg.isNotEmpty()) "$label: $msg" else label
            if (parts.none { it == entry }) {
                parts.add(entry)
            }
            current = current.cause
            depth++
        }
        return parts.joinToString(" → ").ifBlank { "Bilinmeyen hata" }
    }

    /**
     * Cleans any leftover temporary directories from interrupted previous runs.
     */
    fun cleanupOrphanTempDirs(context: Context) {
        runCatching {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith(WORK_DIR_PREFIX)) {
                    file.deleteRecursively()
                }
            }
        }
    }

    fun createWorkDir(context: Context): File {
        val dir = File(context.cacheDir, "${WORK_DIR_PREFIX}${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    /**
     * Obtains a fresh zero-copy SAF read token for content:// URIs, or the direct filesystem path
     * for file:// URIs. Must be called per FFmpeg/FFprobe invocation because SAF FDs are closed
     * after each command finishes.
     */
    fun resolveInputPath(context: Context, uri: Uri): String {
        return if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
            uri.path!!
        } else {
            try {
                ensureSilentLogcat()
                FFmpegKitConfig.getSafParameterForRead(context, uri)
                    ?: throw IllegalStateException("Dosya okuma erişimi alınamadı (SAF)")
            } catch (t: Throwable) {
                if (t is LinkageError || t is ExceptionInInitializerError) {
                    if (nativeInitFailure == null) nativeInitFailure = t
                }
                throw t
            }
        }
    }

    /**
     * Probes media metadata without copying the file to cache (zero-copy even on 15 GB MKV files).
     */
    suspend fun probeMedia(context: Context, uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val (displayName, sizeFromCursor) = queryFileNameAndSize(context, uri)
        val safeFileName = displayName.ifBlank { "medya.mp4" }
        val dotIndex = safeFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) safeFileName.substring(0, dotIndex) else safeFileName
        val ext = if (dotIndex > 0 && dotIndex < safeFileName.lastIndex) {
            safeFileName.substring(dotIndex + 1)
        } else {
            ""
        }

        val safPath = resolveInputPath(context, uri)
        val session = FFprobeKit.getMediaInformation(safPath)
        val info = session.mediaInformation

        val durationSec = info?.duration?.toDoubleOrNull()
        val sizeBytes = when {
            sizeFromCursor > 0L -> sizeFromCursor
            else -> info?.size?.toLongOrNull() ?: 0L
        }

        var vCodec: String? = null
        var width: Int? = null
        var height: Int? = null
        var pixFmt: String? = null
        var aCodec: String? = null

        val streams = info?.streams.orEmpty()
        for (stream in streams) {
            val type = stream.type?.lowercase(Locale.US)
            if (type == "video" && vCodec == null) {
                vCodec = stream.codec
                width = stream.width?.toInt()
                height = stream.height?.toInt()
                pixFmt = stream.getStringProperty("pix_fmt")
            } else if (type == "audio" && aCodec == null) {
                aCodec = stream.codec
            }
        }

        MediaInfo(
            uri = uri,
            fileName = safeFileName,
            baseName = baseName,
            extension = ext,
            sizeBytes = sizeBytes,
            durationSec = durationSec,
            videoCodec = vCodec,
            width = width,
            height = height,
            pixFmt = pixFmt,
            audioCodec = aCodec
        )
    }

    /**
     * Dynamically checks if bundled libx264 and libx265 encoders support yuv420p10le (10-bit).
     * Captures any native initialization failure instead of swallowing it silently.
     */
    suspend fun probeEncoderCapabilities(): EncoderCapabilities = withContext(Dispatchers.IO) {
        cachedCapabilities?.let { return@withContext it }
        try {
            ensureSilentLogcat()
            val x264Out = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-h", "encoder=libx264"))
                .allLogsAsString.orEmpty()
            val x265Out = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-h", "encoder=libx265"))
                .allLogsAsString.orEmpty()

            val caps = EncoderCapabilities(
                x264Supports10Bit = x264Out.contains("yuv420p10le"),
                x265Supports10Bit = x265Out.contains("yuv420p10le"),
                probed = true
            )
            cachedCapabilities = caps
            caps
        } catch (t: Throwable) {
            if (nativeInitFailure == null) {
                nativeInitFailure = t
            }
            EncoderCapabilities(probed = false)
        }
    }

    /**
     * Exact port of show_kf_at(): scans packet headers within [target - 15s, +28s] without
     * decoding frames to find prev/next/nearby keyframes in milliseconds.
     */
    suspend fun scanKeyframes(
        context: Context,
        uri: Uri,
        targetSec: Double,
        isStart: Boolean
    ): KeyframePointResult = withContext(Dispatchers.IO) {
        val seekS = (targetSec.toInt() - 15).coerceAtLeast(0)
        val readSpan = (targetSec.toInt() + 16 - seekS).coerceAtLeast(32)
        val safPath = resolveInputPath(context, uri)
        val args = arrayOf(
            "-v", "error",
            "-select_streams", "v:0",
            "-read_intervals", "${seekS}%+${readSpan}",
            "-show_packets",
            "-show_entries", "packet=pts_time,dts_time,flags",
            "-of", "compact=p=0",
            safPath
        )
        val session = FFprobeKit.executeWithArguments(args)
        val output = session.output.orEmpty()
        FfmpegMath.parseKeyframePackets(output, targetSec, isStart)
    }

    /**
     * Extracts a lightweight square-ready preview thumbnail at [timeSec] using Android's native
     * [MediaMetadataRetriever] first (15-40ms on gallery MP4/MOV), falling back to a single-frame
     * FFmpeg seek for exotic containers like MKV/10-bit HEVC.
     */
    suspend fun extractPreviewFrame(
        context: Context,
        uri: Uri,
        timeSec: Double,
        durationSec: Double?
    ): Bitmap? = withContext(Dispatchers.IO) {
        val maxSec = durationSec?.let { (it - 0.25).coerceAtLeast(0.0) }
        val clampedSec = when {
            maxSec != null -> timeSec.coerceIn(0.0, maxSec)
            else -> timeSec.coerceAtLeast(0.0)
        }
        val timeUs = (clampedSec * 1_000_000.0).toLong().coerceAtLeast(0L)
        val backoffTimeUs = ((clampedSec - 1.0).coerceAtLeast(0.0) * 1_000_000.0).toLong()

        fun decodeWithRetriever(retriever: MediaMetadataRetriever, targetUs: Long): Bitmap? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 480)
                    ?: retriever.getScaledFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 480, 480)
                    ?: retriever.getScaledFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
            } else {
                retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                    ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }

        // 1) Fast hardware-backed MediaMetadataRetriever via ParcelFileDescriptor (most reliable on Android 14-16 Photo Picker & SAF)
        val retrieverBitmap = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                val pfd = if (!uri.scheme.equals("file", ignoreCase = true)) {
                    runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                } else {
                    null
                }
                if (pfd != null) {
                    pfd.use { fd ->
                        retriever.setDataSource(fd.fileDescriptor)
                        decodeWithRetriever(retriever, timeUs)
                            ?: if (backoffTimeUs < timeUs) decodeWithRetriever(retriever, backoffTimeUs) else null
                    }
                } else {
                    retriever.setDataSource(context, uri)
                    decodeWithRetriever(retriever, timeUs)
                        ?: if (backoffTimeUs < timeUs) decodeWithRetriever(retriever, backoffTimeUs) else null
                }
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()

        if (retrieverBitmap != null) return@withContext retrieverBitmap

        // 2) Fallback: serialized single-frame FFmpeg extract for MKV / HEVC 10-bit / exotic codecs
        previewFallbackMutex.withLock {
            suspend fun runFfmpegThumb(sec: Double): Bitmap? = runCatching {
                ensureSilentLogcat()
                val tmpFile = File(context.cacheDir, "fflab_thumb_${System.nanoTime()}.jpg")
                try {
                    val safPath = resolveInputPath(context, uri)
                    val ts = String.format(Locale.US, "%.3f", sec)
                    val session = FFmpegKit.executeWithArguments(
                        arrayOf(
                            "-hide_banner",
                            "-loglevel", "error",
                            "-ss", ts,
                            "-i", safPath,
                            "-an", "-sn", "-dn",
                            "-frames:v", "1",
                            "-vf", "scale='min(480,iw)':-2",
                            "-q:v", "4",
                            "-y", tmpFile.absolutePath
                        )
                    )
                    if (ReturnCode.isSuccess(session.returnCode) && tmpFile.isFile && tmpFile.length() > 0L) {
                        BitmapFactory.decodeFile(tmpFile.absolutePath)
                    } else {
                        null
                    }
                } finally {
                    runCatching { tmpFile.delete() }
                }
            }.getOrNull()

            runFfmpegThumb(clampedSec)
                ?: if (clampedSec > 1.0) runFfmpegThumb((clampedSec - 1.0).coerceAtLeast(0.0)) else null
        }
    }

    /**
     * Runs a single FFmpeg command asynchronously with real-time progress callbacks and full
     * coroutine cancellation support. Uses `-loglevel warning` and filters noisy lines so the
     * terminal stays silent during normal encoding (zero UI/CPU waste) and only reports real
     * errors or important warnings.
     */
    suspend fun executeStep(
        args: List<String>,
        expectedDurationSec: Double?,
        onProgress: (currentSec: Double, percent: Int?, speed: String, sizeKb: Long) -> Unit,
        onLogLine: (String) -> Unit
    ): FfmpegStepResult = suspendCancellableCoroutine { cont ->
        ensureSilentLogcat()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val logsBuffer = mutableListOf<String>()
        val fullArgs = buildList {
            add("-hide_banner")
            add("-loglevel")
            add("warning")
            add("-stats")
            if (!args.contains("-filter_threads")) {
                add("-filter_threads")
                add("0")
            }
            addAll(args)
        }.toTypedArray()

        val session = FFmpegKit.executeWithArgumentsAsync(
            fullArgs,
            { completedSession ->
                val rc = completedSession.returnCode
                val isSuccess = ReturnCode.isSuccess(rc)
                val isCancelled = ReturnCode.isCancel(rc)
                val codeVal = rc?.value ?: -1
                if (cont.isActive) {
                    cont.resume(
                        FfmpegStepResult(
                            success = isSuccess,
                            cancelled = isCancelled,
                            returnCode = codeVal,
                            logs = synchronized(logsBuffer) { logsBuffer.toList() }
                        )
                    )
                }
            },
            { log ->
                val msg = log?.message?.trim() ?: return@executeWithArgumentsAsync
                if (isImportantLogLine(msg)) {
                    var added = false
                    synchronized(logsBuffer) {
                        if (logsBuffer.lastOrNull() != msg) {
                            if (logsBuffer.size >= 80) logsBuffer.removeAt(0)
                            logsBuffer.add(msg)
                            added = true
                        }
                    }
                    if (added) {
                        onLogLine(msg)
                    }
                }
            },
            { stats ->
                if (stats != null) {
                    val curSec = (stats.time / 1000.0).coerceAtLeast(0.0)
                    val pct = FfmpegMath.calculatePercent(curSec, expectedDurationSec)
                    val speedStr = if (stats.speed > 0.0) {
                        String.format(Locale.US, "%.2fx", stats.speed)
                    } else {
                        "..."
                    }
                    val kb = (stats.size / 1024L).coerceAtLeast(0L)
                    onProgress(curSec, pct, speedStr, kb)
                }
            }
        )

        cont.invokeOnCancellation {
            runCatching { FFmpegKit.cancel(session.sessionId) }
        }
    }

    private fun isImportantLogLine(line: String): Boolean {
        if (line.isBlank()) return false
        val lower = line.lowercase(Locale.US)
        // Drop continuous progress/stats lines and noisy benign filter/muxer warnings
        if (lower.startsWith("frame=") || lower.startsWith("size=")) return false
        if (lower.contains("changing video frame properties")) return false
        if (lower.contains("filter context - w:")) return false
        if (lower.contains("past duration") && lower.contains("too large")) return false
        if (lower.contains("deprecated pixel format")) return false
        if (lower.contains("non-monotonous dts")) return false
        if (lower.contains("more than 1000 frames duplicated")) return false
        return true
    }

    private fun queryFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
            val file = File(uri.path!!)
            return file.name to file.length()
        }
        var name = ""
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty()
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "video.mp4" } ?: "video.mp4"
        }
        return name to size
    }
}
