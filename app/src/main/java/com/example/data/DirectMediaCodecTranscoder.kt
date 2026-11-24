package com.example.data

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin timing, frame-rate cadence, and HDR metadata helpers for [DirectMediaCodecTranscoder].
 */
internal object DirectTranscodeMath {
    private const val COLOR_TRANSFER_ST2084 = 6
    private const val COLOR_TRANSFER_HLG = 7
    private const val COLOR_STANDARD_BT2020 = 6

    private const val HEVC_PROFILE_MAIN10_HDR10 = 0x1000
    private const val HEVC_PROFILE_MAIN10_HDR10_PLUS = 0x2000
    private const val VP9_PROFILE_2_HDR = 0x1000
    private const val VP9_PROFILE_3_HDR = 0x2000
    private const val VP9_PROFILE_2_HDR10_PLUS = 0x4000
    private const val VP9_PROFILE_3_HDR10_PLUS = 0x8000
    private const val AV1_PROFILE_MAIN10_HDR10 = 0x1000
    private const val AV1_PROFILE_MAIN10_HDR10_PLUS = 0x2000

    private const val MAX_SHARED_AV_OFFSET_US = 2_000_000L

    fun isHdrColorMetadata(
        colorTransfer: Int?,
        colorStandard: Int?,
        profile: Int?,
        mime: String?
    ): Boolean {
        if (colorTransfer == COLOR_TRANSFER_ST2084 || colorTransfer == COLOR_TRANSFER_HLG) {
            return true
        }
        if (colorStandard == COLOR_STANDARD_BT2020) {
            return true
        }
        if (profile != null && !mime.isNullOrBlank()) {
            when {
                mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> {
                    if (profile == HEVC_PROFILE_MAIN10_HDR10 || profile == HEVC_PROFILE_MAIN10_HDR10_PLUS) {
                        return true
                    }
                }
                mime.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) -> {
                    if (profile == VP9_PROFILE_2_HDR ||
                        profile == VP9_PROFILE_3_HDR ||
                        profile == VP9_PROFILE_2_HDR10_PLUS ||
                        profile == VP9_PROFILE_3_HDR10_PLUS
                    ) {
                        return true
                    }
                }
                mime.equals("video/av01", ignoreCase = true) -> {
                    if (profile == AV1_PROFILE_MAIN10_HDR10 || profile == AV1_PROFILE_MAIN10_HDR10_PLUS) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun isMediaFormatHdr(format: MediaFormat): Boolean {
        val transfer = readIntOrNull(format, MediaFormat.KEY_COLOR_TRANSFER)
        val standard = readIntOrNull(format, MediaFormat.KEY_COLOR_STANDARD)
        val profile = readIntOrNull(format, MediaFormat.KEY_PROFILE)
        val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
        return isHdrColorMetadata(transfer, standard, profile, mime)
    }

    fun isExplicitHdrTransfer(format: MediaFormat): Boolean {
        val transfer = readIntOrNull(format, MediaFormat.KEY_COLOR_TRANSFER)
        return transfer == COLOR_TRANSFER_ST2084 || transfer == COLOR_TRANSFER_HLG
    }

    /**
     * Resolves a shared starting PTS baseline for untrimmed clips so an initial video B-frame
     * or edit-list offset (e.g. video at 80ms, audio at 0ms) never shifts video ahead of audio.
     * Returns `null` if tracks diverge by >2s (malformed container timestamps) so each track
     * safely normalizes independently.
     */
    fun resolveUntrimmedAvBaselineUs(
        firstVideoPtsUs: Long?,
        firstAudioPtsUs: Long?
    ): Long? {
        if (firstVideoPtsUs == null || firstVideoPtsUs < 0L) return null
        if (firstAudioPtsUs == null || firstAudioPtsUs < 0L) return null
        if (abs(firstVideoPtsUs - firstAudioPtsUs) > MAX_SHARED_AV_OFFSET_US) return null
        return minOf(firstVideoPtsUs, firstAudioPtsUs).coerceAtLeast(0L)
    }

    fun sanitizeMonotonicPtsUs(
        candidatePtsUs: Long,
        lastPtsUs: Long,
        minStepUs: Long
    ): Long {
        val safeCandidate = candidatePtsUs.coerceAtLeast(0L)
        return if (lastPtsUs != Long.MIN_VALUE && safeCandidate <= lastPtsUs) {
            lastPtsUs + minStepUs.coerceAtLeast(1L)
        } else {
            safeCandidate
        }
    }

    private fun readIntOrNull(format: MediaFormat, key: String): Int? =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
}

/**
 * Fractional-accumulator frame dropper that hits exact target FPS across 60, 59.94, 50 PAL,
 * 48, 120 FPS, and VFR screen recordings without halving 50 FPS down to 25 FPS.
 */
internal class TargetFpsFrameDropper(
    targetFps: Double = 30.0,
    private val toleranceUs: Double = 2_000.0
) {
    private val stepUs: Double = 1_000_000.0 / targetFps.coerceAtLeast(1.0)
    private var hasKeptFrame = false
    private var nextTargetPtsUs = 0.0

    fun shouldKeepFrame(normalizedPtsUs: Long): Boolean {
        val pts = normalizedPtsUs.toDouble()
        if (!hasKeptFrame) {
            hasKeptFrame = true
            nextTargetPtsUs = pts + stepUs
            return true
        }
        if (pts < nextTargetPtsUs - toleranceUs) {
            return false
        }
        nextTargetPtsUs += stepUs
        // Re-anchor cleanly only after genuine VFR pauses (gap > 1 target frame) so regular
        // fractional cadences like 50 FPS PAL and 48 FPS preserve exact sub-frame phase.
        if (nextTargetPtsUs <= pts) {
            nextTargetPtsUs = pts + stepUs
        }
        return true
    }
}

data class DirectTranscodeRequest(
    val sourceUri: Uri,
    val outputFile: File,
    val tempDir: File,
    val videoMime: String,
    val preferredEncoderName: String?,
    val targetDisplayWidth: Int,
    val targetDisplayHeight: Int,
    val videoBitrateBps: Int,
    val targetFps: Int,
    val useCbr: Boolean,
    val dropTo30Fps: Boolean,
    val hasAudio: Boolean,
    val forceAudioReencode: Boolean,
    val audioBitrateBps: Int,
    val clipStartUs: Long?,
    val clipEndUs: Long?,
    val effectiveDurationSec: Double,
    val requestSdrToneMap: Boolean,
    val wants10BitHevc: Boolean = false,
    val supportsAbgr2101010: Boolean = false,
    val hdrEncoderName: String? = null
)

/**
 * Direct native Android MediaExtractor -> MediaCodec HW Decoder -> OpenGL ES 2.0/3.0 Surface ->
 * MediaCodec HW Encoder -> MediaMuxer hardware transcoder.
 *
 * Mirrors the exact minimal MediaFormat architecture used by standalone Android MediaCodec
 * compressors while supporting:
 * - True 10-bit HEVC Main10 / Main10HDR10 encoding via EGL ES 3.0 RGBA1010102 + COLOR_Format32bitABGR2101010
 * - Strict monotonic PTS sanitization for FFmpeg lossless-cut clips
 * - Verified trimmed audio sync (`SEEK_TO_PREVIOUS_SYNC` + written audio sample verification)
 */
internal object DirectMediaCodecTranscoder {

    private const val TIMEOUT_US = 10_000L
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_OPENGL_ES3_BIT = 0x0040
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
    private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    private const val COLOR_FORMAT_32BIT_ABGR2101010 = 0x7F00AAA2
    const val ERROR_HDR_NEEDS_OPENGL = "DIRECT_HDR_NEEDS_OPENGL"

    fun transcode(
        context: Context,
        request: DirectTranscodeRequest,
        cancelFlag: AtomicBoolean,
        onProgress: (percent: Int, elapsedSec: Double, effectiveDurationSec: Double) -> Unit
    ): Media3TurboResult {
        val startUptimeMs = SystemClock.elapsedRealtime()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        var videoPfd: ParcelFileDescriptor? = null
        var audioPfd: ParcelFileDescriptor? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var inputSurface: CodecInputSurface? = null
        var outputSurface: CodecOutputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var tempAudioFile: File? = null

        try {
            if (cancelFlag.get()) {
                return Media3TurboResult(success = false, cancelled = true)
            }

            // 1. Open video extractor cleanly (supports file://, Photo Picker, and SAF URIs)
            val (vExt, vPfd) = openExtractor(context, request.sourceUri)
            videoExtractor = vExt
            videoPfd = vPfd

            val videoTrackIdx = findTrackIndex(vExt, "video/")
            if (videoTrackIdx < 0) {
                return Media3TurboResult(
                    success = false,
                    isUnsupportedFormat = true,
                    errorCodeName = "DIRECT_NO_VIDEO_TRACK",
                    errorDetail = "No video track found in source media",
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            }

            val inVideoFormat = vExt.getTrackFormat(videoTrackIdx)
            val inVideoMime = inVideoFormat.getString(MediaFormat.KEY_MIME)
                ?: return Media3TurboResult(
                    success = false,
                    isUnsupportedFormat = true,
                    errorCodeName = "DIRECT_UNKNOWN_VIDEO_MIME",
                    errorDetail = "Missing video MIME type",
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )

            // Guard HDR & 10-bit color fidelity:
            // - If 10-bit HEVC is requested on HDR content, require Android 13+ RGBA1010102 surface support
            //   (otherwise hand off to Media3's OpenGL ES 3.0 HDR pipeline).
            // - If 8-bit SDR is requested on HDR content, require Android 12+ decoder SDR tone-mapping.
            val inputIsHdr = DirectTranscodeMath.isMediaFormatHdr(inVideoFormat)
            val wants10BitHevc = request.wants10BitHevc &&
                request.videoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            val canUse10BitSurface = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                request.supportsAbgr2101010

            if (wants10BitHevc) {
                if (inputIsHdr && !canUse10BitSurface) {
                    return Media3TurboResult(
                        success = false,
                        isUnsupportedFormat = false,
                        errorCodeName = ERROR_HDR_NEEDS_OPENGL,
                        errorDetail = "10-bit HDR source requires 10-bit surface / Media3 OpenGL HDR pipeline",
                        elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                    )
                }
            } else if (inputIsHdr && (!request.requestSdrToneMap || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                return Media3TurboResult(
                    success = false,
                    isUnsupportedFormat = false,
                    errorCodeName = ERROR_HDR_NEEDS_OPENGL,
                    errorDetail = "HDR source requires Media3 OpenGL color pipeline",
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            }

            val rotation = resolveVideoRotation(context, request.sourceUri, inVideoFormat)
            val isRotated = rotation == 90 || rotation == 270
            val rawEncW = if (isRotated) request.targetDisplayHeight else request.targetDisplayWidth
            val rawEncH = if (isRotated) request.targetDisplayWidth else request.targetDisplayHeight
            val encW = makeEven(rawEncW.coerceAtLeast(16))
            val encH = makeEven(rawEncH.coerceAtLeast(16))
            val safeFps = request.targetFps.coerceIn(15, 120)

            // Select video track early so we can peek its initial PTS for untrimmed A/V sync baseline
            vExt.selectTrack(videoTrackIdx)
            val clipStartUs = request.clipStartUs ?: 0L
            val clipEndUs = request.clipEndUs
            if (clipStartUs > 0L) {
                vExt.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val initialVideoPtsUs = if (clipStartUs <= 0L) vExt.sampleTime.takeIf { it >= 0L } else null

            // 2. Prepare audio source track (re-encode to AAC first in a fast isolated pass if needed,
            //    so MediaMuxer receives the final AAC csd-0 immediately with zero codec deadlocks)
            var audioTrackIndexInExtractor = -1
            var audioMuxFormat: MediaFormat? = null
            var audioPtsOffsetUs = 0L
            var audioUsesOriginalTimeline = false
            var sharedAvBaselineUs: Long? = null

            if (request.hasAudio) {
                val srcAudioTrackIdx = findTrackIndex(vExt, "audio/")
                if (srcAudioTrackIdx >= 0) {
                    val srcAudioFmt = vExt.getTrackFormat(srcAudioTrackIdx)
                    val srcAudioMime = srcAudioFmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    val isMp4SafeAudio = srcAudioMime.equals("audio/mp4a-latm", ignoreCase = true) ||
                        srcAudioMime.equals("audio/mpeg", ignoreCase = true)
                    val mustTranscodeAudio = request.forceAudioReencode || !isMp4SafeAudio

                    if (mustTranscodeAudio) {
                        val tmpAudio = File(request.tempDir, "direct_audio_${System.nanoTime()}.m4a")
                        tempAudioFile = tmpAudio
                        val (audioOk, computedBaseline) = transcodeAudioToAacFile(
                            context = context,
                            sourceUri = request.sourceUri,
                            outAudioFile = tmpAudio,
                            targetBitrateBps = request.audioBitrateBps.coerceIn(32_000, 768_000),
                            clipStartUs = request.clipStartUs,
                            clipEndUs = request.clipEndUs,
                            untrimmedVideoFirstPtsUs = initialVideoPtsUs,
                            cancelFlag = cancelFlag
                        )
                        if (cancelFlag.get()) {
                            return Media3TurboResult(
                                success = false,
                                cancelled = true,
                                elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            )
                        }
                        if (audioOk && tmpAudio.exists() && tmpAudio.length() > 0L) {
                            val aExt = MediaExtractor()
                            try {
                                aExt.setDataSource(tmpAudio.absolutePath)
                                val idx = findTrackIndex(aExt, "audio/")
                                if (idx >= 0) {
                                    audioExtractor = aExt
                                    audioTrackIndexInExtractor = idx
                                    audioMuxFormat = aExt.getTrackFormat(idx)
                                    aExt.selectTrack(idx)
                                    audioPtsOffsetUs = 0L
                                    audioUsesOriginalTimeline = false
                                    sharedAvBaselineUs = computedBaseline
                                } else {
                                    runCatching { aExt.release() }
                                }
                            } catch (t: Throwable) {
                                runCatching { aExt.release() }
                                throw t
                            }
                        }
                        // Never silently drop audio or bypass target-size audio bitrate compression
                        // when audio re-encoding was explicitly required; hand off to Tier 3 / FFmpeg.
                        if (audioExtractor == null) {
                            return Media3TurboResult(
                                success = false,
                                isUnsupportedFormat = true,
                                errorCodeName = "DIRECT_AUDIO_TRANSCODE_FAILED",
                                errorDetail = "Direct audio transcode failed ($srcAudioMime)",
                                elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            )
                        }
                    } else if (isMp4SafeAudio) {
                        // Pass-through original MP4-safe audio track when re-encode wasn't needed
                        val (aExt, aPfd) = openExtractor(context, request.sourceUri)
                        audioExtractor = aExt
                        audioPfd = aPfd
                        val idx = findTrackIndex(aExt, "audio/")
                        if (idx >= 0) {
                            audioTrackIndexInExtractor = idx
                            audioMuxFormat = aExt.getTrackFormat(idx)
                            aExt.selectTrack(idx)
                            audioUsesOriginalTimeline = true
                            if (clipStartUs > 0L) {
                                // SEEK_TO_PREVIOUS_SYNC guarantees we never jump past clipEndUs on
                                // short trims or drop the opening audio samples; pumpAudioUpTo trims
                                // any pre-roll samples cleanly before clipStartUs.
                                aExt.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                audioPtsOffsetUs = clipStartUs
                            } else {
                                val initialAudioPtsUs = aExt.sampleTime.takeIf { it >= 0L }
                                sharedAvBaselineUs = DirectTranscodeMath.resolveUntrimmedAvBaselineUs(
                                    firstVideoPtsUs = initialVideoPtsUs,
                                    firstAudioPtsUs = initialAudioPtsUs
                                )
                                audioPtsOffsetUs = sharedAvBaselineUs ?: 0L
                            }
                        }
                    }
                }
            }

            if (cancelFlag.get()) {
                return Media3TurboResult(
                    success = false,
                    cancelled = true,
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            }

            // 3. Configure hardware video encoder (with full 10-bit HEVC Main10/HDR10 metadata when requested)
            val inColorTransfer = if (inVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                runCatching { inVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrNull()
            } else null
            val (encoder, used10BitSurface) = createAndConfigureVideoEncoder(
                videoMime = request.videoMime,
                preferredCodecName = if (wants10BitHevc) (request.hdrEncoderName ?: request.preferredEncoderName) else request.preferredEncoderName,
                width = encW,
                height = encH,
                bitrateBps = request.videoBitrateBps,
                fps = safeFps,
                useCbr = request.useCbr,
                wants10BitHevc = wants10BitHevc,
                allow10BitSurface = canUse10BitSurface,
                sourceFormat = inVideoFormat,
                inputIsHdr = inputIsHdr
            )
            videoEncoder = encoder

            val encoderSurface = encoder.createInputSurface()
            val inSurf = CodecInputSurface(
                surface = encoderSurface,
                use10BitEgl = used10BitSurface,
                colorTransfer = if (used10BitSurface) inColorTransfer else null
            )
            inputSurface = inSurf
            inSurf.makeCurrent()
            encoder.start()

            // 4. Configure hardware video decoder targeting our OpenGL ES external texture surface
            val outSurf = CodecOutputSurface(encW, encH, use10BitEgl = used10BitSurface)
            outputSurface = outSurf

            // Clear KEY_ROTATION on decoder input format so SurfaceTexture does NOT auto-rotate
            // decoded frames into the raw coded encW x encH surface before MediaMuxer applies
            // setOrientationHint(rotation) (which otherwise causes double-rotation on portrait clips).
            if (rotation != 0) {
                runCatching { inVideoFormat.setInteger(MediaFormat.KEY_ROTATION, 0) }
            }

            if (!wants10BitHevc && request.requestSdrToneMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    inVideoFormat.setInteger(
                        MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    )
                }
            }

            val decoder = MediaCodec.createDecoderByType(inVideoMime)
            videoDecoder = decoder
            decoder.configure(inVideoFormat, outSurf.surface, null, 0)
            decoder.start()

            // 5. Initialize MediaMuxer with orientation hint
            if (request.outputFile.exists()) {
                runCatching { request.outputFile.delete() }
            }
            val outMuxer = MediaMuxer(request.outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outMuxer
            if (rotation != 0) {
                runCatching { outMuxer.setOrientationHint(rotation) }
            }

            // 6. Main zero-copy GPU Surface transcode loop with strict monotonic PTS sanitization
            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            val audioBuffer = ByteBuffer.allocateDirect(256 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()

            var muxVideoTrack = -1
            var muxAudioTrack = -1
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var audioDone = (audioExtractor == null || audioMuxFormat == null)
            var writtenAudioSamples = 0

            var firstKeptRawPtsUs = if (clipStartUs <= 0L && sharedAvBaselineUs != null) {
                sharedAvBaselineUs
            } else {
                Long.MIN_VALUE
            }
            var lastEncodedPtsUs = Long.MIN_VALUE
            var lastMuxedVideoPtsUs = Long.MIN_VALUE
            var lastMuxedAudioPtsUs = Long.MIN_VALUE
            val frameDropper = if (request.dropTo30Fps) TargetFpsFrameDropper(targetFps = 30.0) else null
            val minFrameStepUs = (1_000_000L / safeFps.coerceAtLeast(15)).coerceAtLeast(4_000L)
            val effectiveDurUs = (request.effectiveDurationSec * 1_000_000.0).toLong().coerceAtLeast(500_000L)
            var lastProgressPostMs = 0L

            fun pumpAudioUpTo(targetPtsUs: Long) {
                val aExt = audioExtractor ?: return
                if (audioDone || !muxerStarted || muxAudioTrack < 0) return
                while (!audioDone && !cancelFlag.get()) {
                    val samplePts = aExt.sampleTime
                    if (samplePts < 0L) {
                        audioDone = true
                        break
                    }
                    if (clipEndUs != null && audioUsesOriginalTimeline && samplePts > clipEndUs) {
                        audioDone = true
                        break
                    }
                    val normalizedAudioPts = (samplePts - audioPtsOffsetUs).coerceAtLeast(0L)
                    if (targetPtsUs != Long.MAX_VALUE && normalizedAudioPts > targetPtsUs + 250_000L) {
                        break
                    }
                    audioBuffer.clear()
                    val sampleSize = aExt.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        audioDone = true
                        break
                    }
                    if (audioPtsOffsetUs > 0L && samplePts < audioPtsOffsetUs) {
                        aExt.advance()
                        continue
                    }
                    val safeAudioPts = DirectTranscodeMath.sanitizeMonotonicPtsUs(
                        candidatePtsUs = normalizedAudioPts,
                        lastPtsUs = lastMuxedAudioPtsUs,
                        minStepUs = 1_000L
                    )
                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = safeAudioPts
                    audioBufferInfo.flags = mapExtractorFlagsToCodecFlags(aExt.sampleFlags)
                    outMuxer.writeSampleData(muxAudioTrack, audioBuffer, audioBufferInfo)
                    writtenAudioSamples++
                    lastMuxedAudioPtsUs = safeAudioPts
                    aExt.advance()
                }
            }

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                val eosDeadlineMs = if (endOfStream) SystemClock.elapsedRealtime() + 5_000L else 0L
                while (!cancelFlag.get()) {
                    val encStatus = encoder.dequeueOutputBuffer(encBufferInfo, if (endOfStream) TIMEOUT_US else 0L)
                    when {
                        encStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                            if (SystemClock.elapsedRealtime() >= eosDeadlineMs) {
                                encoderDone = true
                                break
                            }
                        }
                        encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxVideoTrack = outMuxer.addTrack(encoder.outputFormat)
                                if (audioMuxFormat != null && audioTrackIndexInExtractor >= 0) {
                                    muxAudioTrack = outMuxer.addTrack(audioMuxFormat)
                                }
                                outMuxer.start()
                                muxerStarted = true
                            }
                        }
                        encStatus >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(encStatus)
                            if (encodedData != null) {
                                if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    encBufferInfo.size = 0
                                }
                                if (encBufferInfo.size > 0 && muxerStarted) {
                                    encodedData.position(encBufferInfo.offset)
                                    encodedData.limit(encBufferInfo.offset + encBufferInfo.size)
                                    val safePts = DirectTranscodeMath.sanitizeMonotonicPtsUs(
                                        candidatePtsUs = encBufferInfo.presentationTimeUs,
                                        lastPtsUs = lastMuxedVideoPtsUs,
                                        minStepUs = 1_000L
                                    )
                                    encBufferInfo.presentationTimeUs = safePts
                                    outMuxer.writeSampleData(muxVideoTrack, encodedData, encBufferInfo)
                                    lastMuxedVideoPtsUs = safePts
                                    pumpAudioUpTo(safePts)

                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (nowMs - lastProgressPostMs >= 220L) {
                                        lastProgressPostMs = nowMs
                                        val pct = ((safePts.toDouble() / effectiveDurUs.toDouble()) * 100.0)
                                            .roundToInt()
                                            .coerceIn(0, 99)
                                        onProgress(pct, (nowMs - startUptimeMs) / 1000.0, request.effectiveDurationSec)
                                    }
                                }
                            }
                            encoder.releaseOutputBuffer(encStatus, false)
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }
            }

            while (!encoderDone && !cancelFlag.get()) {
                // Feed compressed video packets to hardware decoder
                if (!inputDone) {
                    val inBufIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inBufIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inBufIdx)
                        if (inBuf != null) {
                            val sampleSize = vExt.readSampleData(inBuf, 0)
                            val samplePts = vExt.sampleTime
                            if (sampleSize < 0 || (clipEndUs != null && samplePts > clipEndUs + 500_000L)) {
                                decoder.queueInputBuffer(
                                    inBufIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val flags = mapExtractorFlagsToCodecFlags(vExt.sampleFlags)
                                decoder.queueInputBuffer(inBufIdx, 0, sampleSize, samplePts.coerceAtLeast(0L), flags)
                                vExt.advance()
                            }
                        }
                    }
                }

                // Drain hardware decoder -> GPU Surface -> hardware encoder
                if (!decoderDone) {
                    val decStatus = decoder.dequeueOutputBuffer(decBufferInfo, TIMEOUT_US)
                    if (decStatus >= 0) {
                        val isEos = (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val rawPtsUs = decBufferInfo.presentationTimeUs
                        val inClipWindow = decBufferInfo.size > 0 &&
                            (clipStartUs <= 0L || rawPtsUs >= clipStartUs) &&
                            (clipEndUs == null || rawPtsUs <= clipEndUs)

                        var shouldRender = inClipWindow
                        var outPtsUs = 0L

                        if (shouldRender) {
                            if (firstKeptRawPtsUs == Long.MIN_VALUE) {
                                firstKeptRawPtsUs = if (clipStartUs > 0L) minOf(rawPtsUs, clipStartUs) else rawPtsUs
                            }
                            val normalized = (rawPtsUs - firstKeptRawPtsUs).coerceAtLeast(0L)
                            if (frameDropper != null && !frameDropper.shouldKeepFrame(normalized)) {
                                shouldRender = false
                            } else {
                                outPtsUs = DirectTranscodeMath.sanitizeMonotonicPtsUs(
                                    candidatePtsUs = normalized,
                                    lastPtsUs = lastEncodedPtsUs,
                                    minStepUs = minFrameStepUs
                                )
                            }
                        }

                        decoder.releaseOutputBuffer(decStatus, shouldRender)
                        if (shouldRender) {
                            outSurf.awaitNewImage()
                            outSurf.drawImage(encW, encH)
                            inSurf.setPresentationTime(outPtsUs * 1_000L)
                            inSurf.swapBuffers()
                            lastEncodedPtsUs = outPtsUs
                        }

                        if (isEos) {
                            decoderDone = true
                            drainEncoder(endOfStream = true)
                        } else {
                            drainEncoder(endOfStream = false)
                        }
                    } else if (decStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Catch OEM decoders that silently ignore KEY_COLOR_TRANSFER_REQUEST and emit
                        // un-tonemapped HDR frames so we hand off to Media3's OpenGL GPU tone-mapper.
                        if (inputIsHdr &&
                            request.requestSdrToneMap &&
                            DirectTranscodeMath.isExplicitHdrTransfer(decoder.outputFormat)
                        ) {
                            return Media3TurboResult(
                                success = false,
                                isUnsupportedFormat = false,
                                errorCodeName = ERROR_HDR_NEEDS_OPENGL,
                                errorDetail = "Hardware decoder ignored SDR tone-map request",
                                elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            )
                        }
                        drainEncoder(endOfStream = false)
                    } else if (decStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        drainEncoder(endOfStream = false)
                    }
                } else {
                    drainEncoder(endOfStream = false)
                }
            }

            if (cancelFlag.get()) {
                return Media3TurboResult(
                    success = false,
                    cancelled = true,
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            }

            // Drain any remaining audio samples cleanly before finalizing MP4 moov atom
            pumpAudioUpTo(Long.MAX_VALUE)

            if (!muxerStarted) {
                return Media3TurboResult(
                    success = false,
                    isUnsupportedFormat = true,
                    errorCodeName = "DIRECT_EMPTY_OUTPUT",
                    errorDetail = "Hardware encoder produced no output frames",
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            }

            // Explicitly stop MediaMuxer inside try so moov flush errors surface cleanly instead of
            // returning success on a truncated file in finally.
            muxerStarted = false
            outMuxer.stop()

            if (!request.outputFile.exists() || request.outputFile.length() == 0L) {
                return Media3TurboResult(
                    success = false,
                    isUnsupportedFormat = true,
                    errorCodeName = "DIRECT_EMPTY_OUTPUT",
                    errorDetail = "Hardware muxer wrote empty output file",
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            }

            val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
            onProgress(100, elapsedSec, request.effectiveDurationSec)
            return Media3TurboResult(
                success = true,
                elapsedSec = elapsedSec,
                outputHasAudio = !request.hasAudio || (muxAudioTrack >= 0 && writtenAudioSamples > 0),
                outputIs10Bit = wants10BitHevc
            )
        } catch (t: Throwable) {
            val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
            if (cancelFlag.get()) {
                return Media3TurboResult(success = false, cancelled = true, elapsedSec = elapsedSec)
            }
            return Media3TurboResult(
                success = false,
                cancelled = false,
                isUnsupportedFormat = true,
                errorCodeName = "DIRECT_MEDIACODEC_ERROR",
                errorDetail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                elapsedSec = elapsedSec
            )
        } finally {
            runCatching { videoDecoder?.stop() }
            runCatching { videoDecoder?.release() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { videoPfd?.close() }
            runCatching { audioPfd?.close() }
            runCatching { tempAudioFile?.delete() }
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
        }
    }

    private fun createAndConfigureVideoEncoder(
        videoMime: String,
        preferredCodecName: String?,
        width: Int,
        height: Int,
        bitrateBps: Int,
        fps: Int,
        useCbr: Boolean,
        wants10BitHevc: Boolean,
        allow10BitSurface: Boolean,
        sourceFormat: MediaFormat,
        inputIsHdr: Boolean
    ): Pair<MediaCodec, Boolean> {
        fun copyColorMetadata(target: MediaFormat) {
            for (intKey in listOf(
                MediaFormat.KEY_COLOR_STANDARD,
                MediaFormat.KEY_COLOR_TRANSFER,
                MediaFormat.KEY_COLOR_RANGE
            )) {
                if (sourceFormat.containsKey(intKey)) {
                    runCatching { target.setInteger(intKey, sourceFormat.getInteger(intKey)) }
                }
            }
            if (sourceFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                runCatching {
                    sourceFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.let { srcBuf ->
                        val dup = srcBuf.duplicate()
                        dup.rewind()
                        target.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, dup)
                    }
                }
            }
        }

        fun buildFormat(withCbr: Boolean, profile: Int?, use10BitSurface: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(videoMime, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    if (use10BitSurface) COLOR_FORMAT_32BIT_ABGR2101010
                    else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (profile != null) {
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                }
                if (wants10BitHevc) {
                    copyColorMetadata(this)
                }
                if (withCbr) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                }
            }

        fun openCodec(): MediaCodec = if (!preferredCodecName.isNullOrBlank()) {
            runCatching { MediaCodec.createByCodecName(preferredCodecName) }
                .getOrElse { MediaCodec.createEncoderByType(videoMime) }
        } else {
            MediaCodec.createEncoderByType(videoMime)
        }

        if (wants10BitHevc) {
            val inTransfer = if (sourceFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                runCatching { sourceFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrNull()
            } else null
            val primaryProfile = if (inTransfer == MediaFormat.COLOR_TRANSFER_ST2084) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            } else {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            }
            val attempts = buildList {
                if (allow10BitSurface) {
                    if (useCbr) add(Triple(true, primaryProfile, true))
                    add(Triple(false, primaryProfile, true))
                    if (primaryProfile != MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                        add(Triple(false, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, true))
                    }
                }
                // For 10-bit SDR sources (or devices accepting Main10 with standard Surface), try Main10
                if (!inputIsHdr) {
                    if (useCbr) add(Triple(true, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, false))
                    add(Triple(false, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, false))
                }
            }
            var lastErr: Throwable? = null
            for ((cbr, prof, surf10) in attempts) {
                val codec = openCodec()
                try {
                    codec.configure(buildFormat(cbr, prof, surf10), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return codec to surf10
                } catch (t: Throwable) {
                    lastErr = t
                    runCatching { codec.release() }
                }
            }
            throw lastErr ?: IllegalStateException("10-bit HEVC hardware encoder configuration failed")
        }

        if (useCbr) {
            val cbrCodec = openCodec()
            try {
                cbrCodec.configure(buildFormat(withCbr = true, profile = null, use10BitSurface = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return cbrCodec to false
            } catch (_: Throwable) {
                runCatching { cbrCodec.release() }
            }
        }

        val vbrCodec = openCodec()
        try {
            vbrCodec.configure(buildFormat(withCbr = false, profile = null, use10BitSurface = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return vbrCodec to false
        } catch (t: Throwable) {
            runCatching { vbrCodec.release() }
            throw t
        }
    }

    /**
     * Fast isolated audio-only transcode (MediaExtractor -> HW/SW Audio Decoder -> AAC Encoder -> M4A)
     * so target-size audio bitrate is honored accurately with zero video/audio codec deadlocks.
     */
    private fun transcodeAudioToAacFile(
        context: Context,
        sourceUri: Uri,
        outAudioFile: File,
        targetBitrateBps: Int,
        clipStartUs: Long?,
        clipEndUs: Long?,
        untrimmedVideoFirstPtsUs: Long?,
        cancelFlag: AtomicBoolean
    ): Pair<Boolean, Long?> {
        var pfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            val (ext, fd) = openExtractor(context, sourceUri)
            extractor = ext
            pfd = fd
            val audioIdx = findTrackIndex(ext, "audio/")
            if (audioIdx < 0) return false to null

            val inFmt = ext.getTrackFormat(audioIdx)
            val inMime = inFmt.getString(MediaFormat.KEY_MIME) ?: return false to null
            var encSampleRate = if (inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8_000, 48_000)
            } else 48_000
            var encChannels = if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 6)
            } else 2

            ext.selectTrack(audioIdx)
            val startUs = clipStartUs ?: 0L
            val sharedBaselineUs = if (startUs > 0L) {
                ext.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                null
            } else {
                val initialAudioPtsUs = ext.sampleTime.takeIf { it >= 0L }
                DirectTranscodeMath.resolveUntrimmedAvBaselineUs(
                    firstVideoPtsUs = untrimmedVideoFirstPtsUs,
                    firstAudioPtsUs = initialAudioPtsUs
                )
            }

            val dec = MediaCodec.createDecoderByType(inMime)
            decoder = dec
            dec.configure(inFmt, null, null, 0)
            dec.start()

            val outMux = MediaMuxer(outAudioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outMux

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var muxTrack = -1
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var firstPtsUs = if (startUs <= 0L && sharedBaselineUs != null) sharedBaselineUs else Long.MIN_VALUE
            var lastMuxPtsUs = Long.MIN_VALUE

            // Lazily configure the AAC encoder after the decoder reports its real PCM sample rate
            // and channel count (fixes HE-AAC SBR/PS streams that decode at 2x container rate).
            fun ensureEncoderStarted(sr: Int, ch: Int): MediaCodec {
                encoder?.let { return it }
                encSampleRate = sr.coerceIn(8_000, 48_000)
                encChannels = ch.coerceIn(1, 6)
                val outFmt = MediaFormat.createAudioFormat("audio/mp4a-latm", encSampleRate, encChannels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateBps)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                }
                val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
                encoder = enc
                enc.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                return enc
            }

            fun drainAudioEncoder(enc: MediaCodec, endOfStream: Boolean) {
                val eosDeadlineMs = if (endOfStream) SystemClock.elapsedRealtime() + 5_000L else 0L
                while (!cancelFlag.get()) {
                    val encOutIdx = enc.dequeueOutputBuffer(encInfo, if (endOfStream) TIMEOUT_US else 0L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                            if (SystemClock.elapsedRealtime() >= eosDeadlineMs) {
                                encoderDone = true
                                break
                            }
                        }
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxTrack = outMux.addTrack(enc.outputFormat)
                                outMux.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            val outBuf = enc.getOutputBuffer(encOutIdx)
                            if (outBuf != null && muxerStarted && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0) {
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                val safePts = DirectTranscodeMath.sanitizeMonotonicPtsUs(
                                    candidatePtsUs = encInfo.presentationTimeUs,
                                    lastPtsUs = lastMuxPtsUs,
                                    minStepUs = 1_000L
                                )
                                encInfo.presentationTimeUs = safePts
                                outMux.writeSampleData(muxTrack, outBuf, encInfo)
                                lastMuxPtsUs = safePts
                            }
                            enc.releaseOutputBuffer(encOutIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }
            }

            while (!encoderDone && !cancelFlag.get()) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = ext.readSampleData(buf, 0)
                            val pts = ext.sampleTime
                            if (size < 0 || (clipEndUs != null && pts > clipEndUs + 200_000L)) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, pts.coerceAtLeast(0L), mapExtractorFlagsToCodecFlags(ext.sampleFlags))
                                ext.advance()
                            }
                        }
                    }
                }

                if (!decoderDone) {
                    val decIdx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (decIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decFmt = dec.outputFormat
                        val actualSr = if (decFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            runCatching { decFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(encSampleRate)
                        } else encSampleRate
                        val actualCh = if (decFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            runCatching { decFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(encChannels)
                        } else encChannels
                        if (encoder == null) {
                            ensureEncoderStarted(actualSr, actualCh)
                        }
                    } else if (decIdx >= 0) {
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pcmBuf = dec.getOutputBuffer(decIdx)
                        val inRange = decInfo.size > 0 &&
                            (startUs <= 0L || decInfo.presentationTimeUs >= startUs) &&
                            (clipEndUs == null || decInfo.presentationTimeUs <= clipEndUs)

                        if (pcmBuf != null && inRange) {
                            val enc = ensureEncoderStarted(encSampleRate, encChannels)
                            if (firstPtsUs == Long.MIN_VALUE) {
                                firstPtsUs = if (startUs > 0L) minOf(decInfo.presentationTimeUs, startUs) else decInfo.presentationTimeUs
                            }
                            pcmBuf.position(decInfo.offset)
                            pcmBuf.limit(decInfo.offset + decInfo.size)
                            val normPts = (decInfo.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)
                            val bytesPerSec = (encSampleRate.toLong() * encChannels.toLong() * 2L).coerceAtLeast(16_000L)
                            var chunkOffsetBytes = 0L
                            var remaining = pcmBuf.remaining()
                            while (remaining > 0 && !cancelFlag.get()) {
                                val encInIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                                if (encInIdx >= 0) {
                                    val encInBuf = enc.getInputBuffer(encInIdx)
                                    if (encInBuf != null) {
                                        encInBuf.clear()
                                        val toCopy = minOf(remaining, encInBuf.remaining())
                                        val savedLimit = pcmBuf.limit()
                                        pcmBuf.limit(pcmBuf.position() + toCopy)
                                        encInBuf.put(pcmBuf)
                                        pcmBuf.limit(savedLimit)
                                        val chunkPtsUs = normPts + (chunkOffsetBytes * 1_000_000L) / bytesPerSec
                                        enc.queueInputBuffer(encInIdx, 0, toCopy, chunkPtsUs, 0)
                                        chunkOffsetBytes += toCopy
                                        remaining -= toCopy
                                    }
                                } else {
                                    // Drain encoder to avoid input starvation
                                    drainAudioEncoder(enc, endOfStream = false)
                                }
                            }
                        }
                        dec.releaseOutputBuffer(decIdx, false)
                        if (isEos) {
                            decoderDone = true
                            val enc = encoder
                            if (enc == null) {
                                encoderDone = true
                            } else {
                                var eosQueued = false
                                val eosQueueDeadlineMs = SystemClock.elapsedRealtime() + 5_000L
                                while (!eosQueued && !cancelFlag.get()) {
                                    val encInIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                                    if (encInIdx >= 0) {
                                        enc.queueInputBuffer(encInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        eosQueued = true
                                    } else {
                                        // Drain encoder output while waiting for an input slot so EOS never deadlocks
                                        drainAudioEncoder(enc, endOfStream = false)
                                        if (SystemClock.elapsedRealtime() >= eosQueueDeadlineMs) {
                                            encoderDone = true
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Drain AAC encoder output
                encoder?.let { drainAudioEncoder(it, endOfStream = decoderDone) }
            }
            if (cancelFlag.get() || !muxerStarted) {
                return false to null
            }
            muxerStarted = false
            outMux.stop()
            (outAudioFile.exists() && outAudioFile.length() > 0L) to sharedBaselineUs
        } catch (_: Throwable) {
            false to null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun openExtractor(context: Context, uri: Uri): Pair<MediaExtractor, ParcelFileDescriptor?> {
        val extractor = MediaExtractor()
        try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
                return extractor to null
            }
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (pfd != null) {
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    return extractor to pfd
                } catch (_: Throwable) {
                    runCatching { pfd.close() }
                }
            }
            extractor.setDataSource(context, uri, null)
            return extractor to null
        } catch (t: Throwable) {
            runCatching { extractor.release() }
            throw t
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun resolveVideoRotation(context: Context, uri: Uri, format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            val r = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrNull()
            if (r != null) return ((r % 360) + 360) % 360
        }
        val retriever = MediaMetadataRetriever()
        return try {
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
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun mapExtractorFlagsToCodecFlags(sampleFlags: Int): Int {
        var flags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun makeEven(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else safe - 1
    }

    /**
     * EGL14 window surface wrapping MediaCodec's input Surface for zero-copy GPU frame delivery.
     * Supports both 8-bit ES 2.0 surfaces and 10-bit ES 3.0 RGBA1010102 (+ BT.2020 PQ/HLG) surfaces.
     */
    private class CodecInputSurface(
        private val surface: Surface,
        private val use10BitEgl: Boolean = false,
        private val colorTransfer: Int? = null
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
                val version = IntArray(2)
                require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL14" }

                val config = chooseEglConfig(eglDisplay, use10BitEgl)
                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, if (use10BitEgl) 3 else 2,
                    EGL14.EGL_NONE
                )
                eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                require(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }

                val surfaceAttribs = buildWindowSurfaceAttribs(eglDisplay, use10BitEgl, colorTransfer)
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
                if (eglSurface == EGL14.EGL_NO_SURFACE && surfaceAttribs.size > 1) {
                    eglSurface = EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        config,
                        surface,
                        intArrayOf(EGL14.EGL_NONE),
                        0
                    )
                }
                require(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
            } catch (t: Throwable) {
                runCatching { release() }
                throw t
            }
        }

        private fun buildWindowSurfaceAttribs(
            display: EGLDisplay,
            wants10Bit: Boolean,
            transfer: Int?
        ): IntArray {
            if (!wants10Bit || transfer == null) return intArrayOf(EGL14.EGL_NONE)
            val extensions = runCatching { EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) }.getOrNull().orEmpty()
            return when {
                transfer == MediaFormat.COLOR_TRANSFER_ST2084 &&
                    extensions.contains("EGL_EXT_gl_colorspace_bt2020_pq") ->
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE)
                transfer == MediaFormat.COLOR_TRANSFER_HLG &&
                    extensions.contains("EGL_EXT_gl_colorspace_bt2020_hlg") ->
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
                else -> intArrayOf(EGL14.EGL_NONE)
            }
        }

        private fun chooseEglConfig(display: EGLDisplay, wants10Bit: Boolean): EGLConfig {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (wants10Bit) {
                val tenBitRecordable = intArrayOf(
                    EGL14.EGL_RED_SIZE, 10,
                    EGL14.EGL_GREEN_SIZE, 10,
                    EGL14.EGL_BLUE_SIZE, 10,
                    EGL14.EGL_ALPHA_SIZE, 2,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
                )
                if (EGL14.eglChooseConfig(display, tenBitRecordable, 0, configs, 0, configs.size, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
                ) {
                    return configs[0]!!
                }
                val tenBitPlain = intArrayOf(
                    EGL14.EGL_RED_SIZE, 10,
                    EGL14.EGL_GREEN_SIZE, 10,
                    EGL14.EGL_BLUE_SIZE, 10,
                    EGL14.EGL_ALPHA_SIZE, 2,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL14.EGL_NONE
                )
                require(
                    EGL14.eglChooseConfig(display, tenBitPlain, 0, configs, 0, configs.size, numConfigs, 0) &&
                        numConfigs[0] > 0 && configs[0] != null
                ) { "Unable to choose 10-bit RGBA1010102 EGLConfig" }
                return configs[0]!!
            }
            val recordableAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(display, recordableAttribs, 0, configs, 0, configs.size, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
            ) {
                return configs[0]!!
            }
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            require(
                EGL14.eglChooseConfig(display, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
            ) { "Unable to choose EGLConfig" }
            return configs[0]!!
        }

        fun makeCurrent() {
            require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed"
            }
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            surface.release()
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    /**
     * External OES SurfaceTexture receiver + OpenGL ES 2.0/3.0 quad renderer with dedicated Looper
     * thread so SurfaceTexture.onFrameAvailable never deadlocks the transcode thread.
     */
    private class CodecOutputSurface(
        width: Int,
        height: Int,
        use10BitEgl: Boolean = false
    ) : SurfaceTexture.OnFrameAvailableListener {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val frameSyncObject = Object()
        private var frameAvailable = false
        private val callbackThread = HandlerThread(
            "DirectMC-SurfaceTex",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).apply { start() }
        private val callbackHandler = Handler(callbackThread.looper)

        private val triangleVerticesData = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )
        private val triangleVertices: FloatBuffer = ByteBuffer
            .allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(triangleVerticesData)
                position(0)
            }

        private val mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private val stMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

        private var program: Int = 0
        private var textureId: Int = 0
        private val mvpMatrixHandle: Int
        private val stMatrixHandle: Int
        private val positionHandle: Int
        private val textureHandle: Int

        private val surfaceTexture: SurfaceTexture
        val surface: Surface

        init {
            var createdTexId = 0
            var createdProgram = 0
            var createdSt: SurfaceTexture? = null
            var createdSurface: Surface? = null
            try {
                createdProgram = if (use10BitEgl) {
                    runCatching { createProgram(VERTEX_SHADER_ES3, FRAGMENT_SHADER_ES3) }
                        .getOrElse { createProgram(VERTEX_SHADER, FRAGMENT_SHADER) }
                } else {
                    createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                }
                program = createdProgram
                positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
                textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
                mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
                stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                createdTexId = textures[0]
                textureId = createdTexId
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                createdSt = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener(this@CodecOutputSurface, callbackHandler)
                }
                surfaceTexture = createdSt
                createdSurface = Surface(surfaceTexture)
                surface = createdSurface
            } catch (t: Throwable) {
                runCatching { createdSurface?.release() }
                runCatching { createdSt?.setOnFrameAvailableListener(null) }
                runCatching { createdSt?.release() }
                if (createdProgram != 0) runCatching { GLES20.glDeleteProgram(createdProgram) }
                if (createdTexId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(createdTexId), 0) }
                runCatching { callbackThread.quitSafely() }
                throw t
            }
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            synchronized(frameSyncObject) {
                frameAvailable = true
                frameSyncObject.notifyAll()
            }
        }

        fun awaitNewImage(timeoutMs: Long = 2_500L) {
            synchronized(frameSyncObject) {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (!frameAvailable) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        throw RuntimeException("SurfaceTexture frame wait timed out")
                    }
                    frameSyncObject.wait(remaining)
                }
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
        }

        fun drawImage(outWidth: Int, outHeight: Int) {
            surfaceTexture.getTransformMatrix(stMatrix)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            triangleVertices.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 20, triangleVertices)
            GLES20.glEnableVertexAttribArray(positionHandle)

            triangleVertices.position(3)
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 20, triangleVertices)
            GLES20.glEnableVertexAttribArray(textureHandle)

            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            runCatching { surface.release() }
            runCatching { surfaceTexture.setOnFrameAvailableListener(null) }
            runCatching { surfaceTexture.release() }
            runCatching { GLES20.glDeleteProgram(program) }
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            runCatching { callbackThread.quitSafely() }
        }

        companion object {
            private const val VERTEX_SHADER = """
                uniform mat4 uMVPMatrix;
                uniform mat4 uSTMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """

            private const val VERTEX_SHADER_ES3 = """#version 300 es
                uniform mat4 uMVPMatrix;
                uniform mat4 uSTMatrix;
                in vec4 aPosition;
                in vec4 aTextureCoord;
                out vec2 vTextureCoord;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """

            private const val FRAGMENT_SHADER_ES3 = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision highp float;
                in vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(sTexture, vTextureCoord);
                }
            """

            private fun loadShader(shaderType: Int, source: String): Int {
                val shader = GLES20.glCreateShader(shaderType)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw RuntimeException("Failed to compile GLSL shader: $log")
                }
                return shader
            }

            private fun createProgram(vertexSource: String, fragmentSource: String): Int {
                val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
                val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vs)
                GLES20.glAttachShader(program, fs)
                GLES20.glLinkProgram(program)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    val log = GLES20.glGetProgramInfoLog(program)
                    GLES20.glDeleteProgram(program)
                    throw RuntimeException("Failed to link GLSL program: $log")
                }
                return program
            }
        }
    }
}
