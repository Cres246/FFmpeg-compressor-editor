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
import com.example.domain.AppLanguageController
import com.example.domain.EncoderCapabilities
import com.example.domain.FfmpegMath
import com.example.domain.KeyframePointResult
import com.example.domain.MediaInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class FfmpegStepResult(
    val success: Boolean,
    val cancelled: Boolean,
    val returnCode: Int,
    val logs: List<String>,
    val rawOutput: String = ""
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
    private val activeSessionCompletion = AtomicReference<CompletableDeferred<Unit>?>(null)

    /**
     * Cancels any running native FFmpegKit session and suspends until FFmpegKit's native
     * worker thread actually invokes its completion callback (ensuring all libx264 4K buffers
     * and SAF file descriptors are fully freed before MediaCodec initializes).
     */
    suspend fun awaitActiveSessionTermination(timeoutMs: Long = 5_000L) {
        val pending = activeSessionCompletion.get() ?: return
        if (pending.isCompleted) return
        runCatching { FFmpegKit.cancel() }
        withTimeoutOrNull(timeoutMs) {
            runCatching { pending.await() }
        }
    }

    /**
     * Prevents FFmpegKit from dumping normal stdout/stderr (such as `-h encoder=libx264` probes)
     * into Android Logcat at `E/ffmpeg-kit` error level, while still delivering full logs to
     * `session.allLogsAsString` and per-session callbacks.
     */
    internal fun ensureSilentLogcat() {
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
        return parts.joinToString(" → ").ifBlank { AppLanguageController.currentStrings().errUnknown }
    }

    private val orphanCleanupDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Cleans any leftover temporary directories from interrupted previous runs.
     * Runs once per process and only when no active encoding job is running so Activity
     * recreation while EncodingForegroundService is active never wipes live workDirs.
     */
    suspend fun cleanupOrphanTempDirsIfIdle(context: Context, isIdle: () -> Boolean) = withContext(Dispatchers.IO) {
        if (!isIdle()) return@withContext
        if (!orphanCleanupDone.compareAndSet(false, true)) return@withContext
        if (!isIdle()) {
            orphanCleanupDone.set(false)
            return@withContext
        }
        cleanupOrphanTempDirs(context)
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
                    ?: throw IllegalStateException(AppLanguageController.currentStrings().errSafReadFailed)
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
     * Resolves display-rotated portrait dimensions, excludes embedded cover art (`attached_pic`),
     * and extracts source audio bitrate & channel count across MP4/MOV/MKV/WebM/MP3/FLAC.
     */
    suspend fun probeMedia(context: Context, uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val (displayName, sizeFromCursor) = queryFileNameAndSize(context, uri)
        val safeFileName = displayName.ifBlank { "media.mp4" }
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
            ?: throw IllegalStateException(AppLanguageController.currentStrings().errUnsupportedMediaFile)

        val durationSec = info.duration?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val sizeBytes = when {
            sizeFromCursor > 0L -> sizeFromCursor
            else -> info.size?.toLongOrNull() ?: 0L
        }

        var vCodec: String? = null
        var width: Int? = null
        var height: Int? = null
        var pixFmt: String? = null
        var fps: Double? = null
        var vProfile: String? = null
        var colorTransfer: String? = null
        var colorSpace: String? = null
        var colorPrimaries: String? = null
        var aCodec: String? = null
        var aBitrateKbps: Int? = null
        var aChannels: Int? = null

        val streams = info.streams.orEmpty()
        val videoStreams = streams.filter { it.type?.equals("video", ignoreCase = true) == true }
        // Strictly exclude embedded cover art (`disposition.attached_pic == 1`) so MP3/FLAC/M4A
        // files with album art are never misidentified as videos.
        val primaryVideoStream = videoStreams.firstOrNull { stream ->
            val attachedPic = runCatching {
                stream.allProperties?.optJSONObject("disposition")?.optInt("attached_pic", 0)
            }.getOrNull() ?: 0
            attachedPic != 1
        }

        if (primaryVideoStream != null) {
            vCodec = primaryVideoStream.codec
            val rawW = primaryVideoStream.width?.toInt()?.takeIf { it > 0 }
            val rawH = primaryVideoStream.height?.toInt()?.takeIf { it > 0 }
            val rotation = extractStreamRotationDegrees(primaryVideoStream, context, uri)
            if (rawW != null && rawH != null) {
                if (kotlin.math.abs(rotation) % 180 == 90) {
                    width = rawH
                    height = rawW
                } else {
                    width = rawW
                    height = rawH
                }
            }
            pixFmt = primaryVideoStream.getStringProperty("pix_fmt")
            vProfile = primaryVideoStream.getStringProperty("profile")
            colorTransfer = primaryVideoStream.getStringProperty("color_transfer")
            colorSpace = primaryVideoStream.getStringProperty("color_space")
            colorPrimaries = primaryVideoStream.getStringProperty("color_primaries")
            fps = parseFrameRate(primaryVideoStream.getStringProperty("avg_frame_rate"))
                ?: parseFrameRate(primaryVideoStream.getStringProperty("r_frame_rate"))
        }

        val primaryAudioStream = streams.firstOrNull { it.type?.equals("audio", ignoreCase = true) == true }
        if (primaryAudioStream != null) {
            aCodec = primaryAudioStream.codec
            aChannels = runCatching {
                primaryAudioStream.allProperties?.optInt("channels", 0)?.takeIf { it > 0 }
            }.getOrNull()

            var audioBps = extractStreamBitrateBps(primaryAudioStream)
            if (audioBps == null) {
                audioBps = probeAudioBitrateWithExtractor(context, uri)
            }
            if (audioBps == null && primaryVideoStream == null) {
                audioBps = info.bitrate?.toLongOrNull()?.takeIf { it > 0L }
                    ?: if (sizeBytes > 0L && durationSec != null && durationSec > 0.5) {
                        ((sizeBytes * 8.0) / durationSec).toLong().takeIf { it > 0L }
                    } else null
            }
            aBitrateKbps = audioBps
                ?.let { kotlin.math.round(it / 1000.0).toInt() }
                ?.takeIf { it in 8..20_000 }
        }

        if (vCodec.isNullOrBlank() && aCodec.isNullOrBlank()) {
            throw IllegalStateException(AppLanguageController.currentStrings().errUnsupportedMediaFile)
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
            audioCodec = aCodec,
            fps = fps,
            audioBitrateKbps = aBitrateKbps,
            audioChannels = aChannels,
            videoProfile = vProfile,
            colorTransfer = colorTransfer,
            colorSpace = colorSpace,
            colorPrimaries = colorPrimaries
        )
    }

    data class EncodedOutputProbe(
        val hasVideo: Boolean = false,
        val hasAudio: Boolean = false,
        val hasReadableAudioSamples: Boolean = false,
        val videoCodec: String? = null,
        val pixFmt: String? = null,
        val videoProfile: String? = null,
        val is10Bit: Boolean = false,
        val isHdr: Boolean = false
    )

    /**
     * Fast post-encode integrity verification (~20-40ms) on a local output file:
     * 1) Checks MediaExtractor for an actual audio track with >= 1 readable sample (catches silent/empty tracks).
     * 2) Checks FFprobe for real video pixel format & profile (verifies true 10-bit HEVC Main10 output).
     */
    suspend fun probeEncodedOutput(file: File): EncodedOutputProbe = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext EncodedOutputProbe()
        }
        var extractorOpened = false
        var extractorHasVideo = false
        var extractorHasAudio = false
        var readableAudioSamples = false

        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractorOpened = true
            var audioIdx = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) extractorHasVideo = true
                if (mime.startsWith("audio/") && audioIdx < 0) {
                    audioIdx = i
                    extractorHasAudio = true
                }
            }
            if (audioIdx >= 0) {
                extractor.selectTrack(audioIdx)
                val sampleBuf = java.nio.ByteBuffer.allocate(64 * 1024)
                val firstSize = runCatching { extractor.readSampleData(sampleBuf, 0) }.getOrDefault(-1)
                readableAudioSamples = firstSize > 0
            }
        } catch (_: Throwable) {
            // Fall through to FFprobe
        } finally {
            runCatching { extractor.release() }
        }

        val probeInfo = runCatching {
            ensureSilentLogcat()
            FFprobeKit.getMediaInformation(file.absolutePath)?.mediaInformation
        }.getOrNull()

        if (probeInfo == null) {
            return@withContext EncodedOutputProbe(
                hasVideo = extractorHasVideo,
                hasAudio = extractorHasAudio,
                hasReadableAudioSamples = extractorHasAudio && readableAudioSamples
            )
        }

        val streams = probeInfo.streams.orEmpty()
        val videoStream = streams.firstOrNull { stream ->
            stream.type?.equals("video", ignoreCase = true) == true &&
                (runCatching {
                    stream.allProperties?.optJSONObject("disposition")?.optInt("attached_pic", 0)
                }.getOrNull() ?: 0) != 1
        }
        val audioStream = streams.firstOrNull { it.type?.equals("audio", ignoreCase = true) == true }

        val vCodec = videoStream?.codec
        val pixFmt = videoStream?.getStringProperty("pix_fmt")
        val vProfile = videoStream?.getStringProperty("profile")
        val bitsPerSample = videoStream?.getStringProperty("bits_per_raw_sample")?.toIntOrNull() ?: 0
        val colorTr = videoStream?.getStringProperty("color_transfer")?.lowercase(java.util.Locale.US).orEmpty()
        val colorPr = videoStream?.getStringProperty("color_primaries")?.lowercase(java.util.Locale.US).orEmpty()

        val actual10Bit = (pixFmt?.contains("10") == true) ||
            (pixFmt?.contains("12") == true) ||
            bitsPerSample >= 10 ||
            (vProfile?.contains("10", ignoreCase = true) == true)
        val actualHdr = colorTr == "smpte2084" || colorTr == "arib-std-b67" || colorPr.startsWith("bt2020")

        val ffprobeNbFrames = audioStream?.getStringProperty("nb_frames")?.toLongOrNull()
        val ffprobeAudioHasDur = audioStream?.let { st ->
            val dur = st.getStringProperty("duration")?.toDoubleOrNull()
            (dur != null && dur > 0.05) || (ffprobeNbFrames != null && ffprobeNbFrames > 0L)
        } ?: false
        val verifiedAudioSamples = when {
            ffprobeNbFrames == 0L -> false
            extractorOpened -> extractorHasAudio && readableAudioSamples
            else -> audioStream != null && ffprobeAudioHasDur
        }

        EncodedOutputProbe(
            hasVideo = videoStream != null || extractorHasVideo,
            hasAudio = audioStream != null || extractorHasAudio,
            hasReadableAudioSamples = verifiedAudioSamples,
            videoCodec = vCodec,
            pixFmt = pixFmt,
            videoProfile = vProfile,
            is10Bit = actual10Bit,
            isHdr = actualHdr
        )
    }

    data class ClipTimingProbe(
        val firstVideoPtsSec: Double = 0.0,
        val firstAudioPtsSec: Double? = null,
        val durationSec: Double? = null
    )

    /**
     * Fast (~15ms) header-only probe measuring exact starting video/audio stream PTS and container
     * duration of a local pre-cut/prepared clip so Media3 trim offsets stay sample-accurate.
     */
    suspend fun probeClipTiming(file: File): ClipTimingProbe = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext ClipTimingProbe()
        ensureSilentLogcat()
        val info = runCatching {
            FFprobeKit.getMediaInformation(file.absolutePath)?.mediaInformation
        }.getOrNull()

        val durationSec = info?.duration?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val streams = info?.streams.orEmpty()
        val videoStream = streams.firstOrNull { stream ->
            stream.type?.equals("video", ignoreCase = true) == true &&
                (runCatching {
                    stream.allProperties?.optJSONObject("disposition")?.optInt("attached_pic", 0)
                }.getOrNull() ?: 0) != 1
        }
        val audioStream = streams.firstOrNull { it.type?.equals("audio", ignoreCase = true) == true }

        var firstVideoPts = videoStream?.getStringProperty("start_time")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
        val firstAudioPts = audioStream?.getStringProperty("start_time")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }

        if (firstVideoPts == null) {
            firstVideoPts = runCatching {
                val pktSession = FFprobeKit.executeWithArguments(
                    arrayOf(
                        "-v", "error",
                        "-select_streams", "v:0",
                        "-read_intervals", "%+#1",
                        "-show_packets",
                        "-show_entries", "packet=pts_time,dts_time",
                        "-of", "compact=p=0",
                        file.absolutePath
                    )
                )
                val out = pktSession.output.orEmpty()
                var ptsVal: Double? = null
                var dtsVal: Double? = null
                for (line in out.lineSequence()) {
                    for (token in line.split('|')) {
                        when {
                            token.startsWith("pts_time=") ->
                                ptsVal = token.removePrefix("pts_time=").trim().toDoubleOrNull()
                            token.startsWith("dts_time=") ->
                                dtsVal = token.removePrefix("dts_time=").trim().toDoubleOrNull()
                        }
                    }
                    if (ptsVal != null || dtsVal != null) break
                }
                (ptsVal ?: dtsVal)?.takeIf { it.isFinite() }
            }.getOrNull()
        }

        ClipTimingProbe(
            firstVideoPtsSec = firstVideoPts ?: 0.0,
            firstAudioPtsSec = firstAudioPts,
            durationSec = durationSec
        )
    }

    private fun extractStreamRotationDegrees(
        stream: com.arthenica.ffmpegkit.StreamInformation,
        context: Context,
        uri: Uri
    ): Int {
        // 1. Check FFprobe `side_data_list` displaymatrix rotation (standard in MP4/MOV/MKV)
        runCatching {
            val sideDataList = stream.allProperties?.optJSONArray("side_data_list")
            if (sideDataList != null) {
                for (i in 0 until sideDataList.length()) {
                    val item = sideDataList.optJSONObject(i) ?: continue
                    if (item.has("rotation")) {
                        val rot = item.optDouble("rotation", Double.NaN)
                        if (rot.isFinite()) return ((kotlin.math.round(rot).toInt() % 360) + 360) % 360
                    }
                }
            }
        }
        // 2. Check legacy stream `tags.rotate`
        runCatching {
            val tagRot = stream.allProperties?.optJSONObject("tags")?.optString("rotate")?.trim()?.toIntOrNull()
            if (tagRot != null) return ((tagRot % 360) + 360) % 360
        }
        // 3. Fallback to Android MediaMetadataRetriever for MOV/MP4 rotation matrix edge cases
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                    retriever.setDataSource(uri.path)
                } else {
                    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                    if (pfd != null) {
                        pfd.use { retriever.setDataSource(it.fileDescriptor) }
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                }
                val r = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                ((r % 360) + 360) % 360
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(0)
    }

    private fun extractStreamBitrateBps(stream: com.arthenica.ffmpegkit.StreamInformation): Long? {
        val direct = stream.bitrate?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        if (direct != null) return direct
        // MKV/WebM files often store exact per-stream bitrate in `tags.BPS` or `tags.BPS-eng`
        return runCatching {
            val tags = stream.allProperties?.optJSONObject("tags") ?: return@runCatching null
            val keys = tags.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.equals("BPS", ignoreCase = true) || key.startsWith("BPS-", ignoreCase = true)) {
                    val v = tags.optString(key).trim().toLongOrNull()
                    if (v != null && v > 0L) return@runCatching v
                }
            }
            null
        }.getOrNull()
    }

    private fun probeAudioBitrateWithExtractor(context: Context, uri: Uri): Long? {
        val extractor = android.media.MediaExtractor()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                if (pfd != null) {
                    pfd.use { extractor.setDataSource(it.fileDescriptor) }
                } else {
                    extractor.setDataSource(context, uri, null)
                }
            }
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") && fmt.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) {
                    val br = runCatching { fmt.getInteger(android.media.MediaFormat.KEY_BIT_RATE).toLong() }.getOrNull()
                    if (br != null && br > 0L) return br
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun parseFrameRate(raw: String?): Double? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned == "0/0") return null
        val parsed = if (cleaned.contains('/')) {
            val parts = cleaned.split('/')
            if (parts.size != 2) return null
            val num = parts[0].toDoubleOrNull() ?: return null
            val den = parts[1].toDoubleOrNull() ?: return null
            if (den <= 0.0) return null
            num / den
        } else {
            cleaned.toDoubleOrNull() ?: return null
        }
        return if (parsed.isFinite() && parsed in 1.0..240.0) parsed else null
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
     * Scans packet headers without decoding frames to find prev/next/nearby keyframes in
     * milliseconds. Uses a generous [-30s, +35s] window and adaptively widens to [-75s, +80s]
     * for ultra-sparse GOP videos so the user always sees both previous and next keyframe options.
     */
    suspend fun scanKeyframes(
        context: Context,
        uri: Uri,
        targetSec: Double,
        isStart: Boolean,
        durationSec: Double? = null
    ): KeyframePointResult = withContext(Dispatchers.IO) {
        suspend fun runWindowScan(backSec: Int, forwardSec: Int): KeyframePointResult {
            val seekS = (targetSec.toInt() - backSec).coerceAtLeast(0)
            val readSpan = (targetSec.toInt() + forwardSec - seekS).coerceAtLeast(backSec + forwardSec)
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
            return FfmpegMath.parseKeyframePackets(output, targetSec, isStart)
        }

        val initial = runWindowScan(backSec = 30, forwardSec = 35)
        val missingPrev = initial.prevKf == null && targetSec > 1.0
        val hasStrictlyAfter = initial.nearbyKeyframes.any { it > targetSec + 0.05 }
        val farFromEnd = durationSec == null || targetSec < durationSec - 2.0
        val missingNext = (initial.nextKf == null || !hasStrictlyAfter) && farFromEnd

        if (missingPrev || missingNext) {
            runWindowScan(backSec = 75, forwardSec = 80)
        } else {
            initial
        }
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
        onLogLine: (String) -> Unit,
        captureRawOutput: Boolean = false
    ): FfmpegStepResult = suspendCancellableCoroutine { cont ->
        ensureSilentLogcat()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val logsBuffer = mutableListOf<String>()
        val hasExplicitLogLevel = args.any { it.equals("-loglevel", ignoreCase = true) || it.equals("-v", ignoreCase = true) }
        val fullArgs = buildList {
            add("-hide_banner")
            if (!hasExplicitLogLevel) {
                add("-loglevel")
                add(if (captureRawOutput) "info" else "warning")
            }
            add("-stats")
            if (!args.contains("-filter_threads")) {
                add("-filter_threads")
                add("0")
            }
            addAll(args)
        }.toTypedArray()

        val sessionDone = CompletableDeferred<Unit>()
        activeSessionCompletion.getAndSet(sessionDone)?.complete(Unit)

        val session = try {
            FFmpegKit.executeWithArgumentsAsync(
                fullArgs,
                { completedSession ->
                    sessionDone.complete(Unit)
                    activeSessionCompletion.compareAndSet(sessionDone, null)
                    val rc = completedSession.returnCode
                    val isSuccess = ReturnCode.isSuccess(rc)
                    val isCancelled = ReturnCode.isCancel(rc)
                    val codeVal = rc?.value ?: -1
                    val rawOut = if (!isSuccess || captureRawOutput) {
                        sanitizeRawFfmpegOutput(completedSession.allLogsAsString.orEmpty())
                    } else {
                        ""
                    }
                    if (cont.isActive) {
                        cont.resume(
                            FfmpegStepResult(
                                success = isSuccess,
                                cancelled = isCancelled,
                                returnCode = codeVal,
                                logs = synchronized(logsBuffer) { logsBuffer.toList() },
                                rawOutput = rawOut
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
        } catch (t: Throwable) {
            sessionDone.complete(Unit)
            activeSessionCompletion.compareAndSet(sessionDone, null)
            throw t
        }

        cont.invokeOnCancellation {
            runCatching { FFmpegKit.cancel(session.sessionId) }
        }
    }

    /**
     * Executes a native `ffprobe` command via bundled FFprobeKit without injecting FFmpeg-only
     * flags (`-stats`, `-filter_threads`), returning both exit status and the exact raw output.
     */
    suspend fun executeProbeStep(
        args: List<String>,
        onLogLine: (String) -> Unit
    ): FfmpegStepResult = withContext(Dispatchers.IO) {
        ensureSilentLogcat()
        val hasBannerOrVerbosity = args.any {
            it.equals("-hide_banner", ignoreCase = true) ||
                it.equals("-v", ignoreCase = true) ||
                it.equals("-loglevel", ignoreCase = true)
        }
        val fullArgs = buildList {
            if (!hasBannerOrVerbosity) {
                add("-hide_banner")
            }
            addAll(args)
        }.toTypedArray()

        val session = FFprobeKit.executeWithArguments(fullArgs)
        val rc = session.returnCode
        val isSuccess = ReturnCode.isSuccess(rc)
        val isCancelled = ReturnCode.isCancel(rc)
        val codeVal = rc?.value ?: -1

        val rawOut = session.output.orEmpty().trim()
            .ifBlank { session.allLogsAsString.orEmpty().trim() }
            .let { if (it.length > 24_000) it.takeLast(24_000) else it }

        val lines = rawOut.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(120)
            .toList()
        lines.forEach { onLogLine(it) }

        FfmpegStepResult(
            success = isSuccess,
            cancelled = isCancelled,
            returnCode = codeVal,
            logs = lines,
            rawOutput = rawOut
        )
    }

    private fun sanitizeRawFfmpegOutput(raw: String): String {
        if (raw.isBlank()) return ""
        val filtered = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                val lower = line.trimStart().lowercase(Locale.US)
                line.isNotBlank() && !lower.startsWith("frame=") && !lower.startsWith("size=")
            }
            .joinToString("\n")
            .trim()
        val result = filtered.ifBlank { raw.trim() }
        return if (result.length > 12_000) {
            "...\n" + result.takeLast(12_000)
        } else {
            result
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
