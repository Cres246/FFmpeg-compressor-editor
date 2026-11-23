package com.example.data

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.domain.BitDepthOption
import com.example.domain.FfmpegMath
import com.example.domain.FpsOption
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.ResolutionOption
import com.example.domain.TrimSubMode
import com.example.domain.VideoCodecOption
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class Media3TurboResult(
    val success: Boolean,
    val cancelled: Boolean = false,
    val isUnsupportedFormat: Boolean = false,
    val errorCodeName: String? = null,
    val errorDetail: String? = null,
    val elapsedSec: Double = 0.0
)

/**
 * Native Jetpack Media3 Transformer hardware-accelerated transcoding engine.
 * - Never instantiated or loaded unless the user explicitly activates "Acelem Var" (Hardware Turbo).
 * - Uses Android MediaCodec hardware decoders/encoders + Zero-Copy GPU Surface pipeline.
 * - Honors rotation metadata (no letterboxing on 90°/270° portrait phone recordings),
 *   3-way aspect-ratio scaling, FPS capping (skipping GL pass when already <= 30 FPS),
 *   smart AAC stream pass-through, and automatic two-tier encoder settings fallback on OEM HAL quirks.
 */
@OptIn(UnstableApi::class)
object Media3TurboEngine {

    private val activeCancelAction = AtomicReference<(() -> Unit)?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancelActive() {
        val action = activeCancelAction.getAndSet(null) ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { action() }
        } else {
            mainHandler.post { runCatching { action() } }
        }
    }

    /**
     * Computes target video bitrate in bits/sec for Media3 VideoEncoderSettings while preserving
     * the user's exact target MB, resolution, FPS, and codec choices.
     */
    fun resolveTargetVideoBitrateBps(media: MediaInfo, config: ModeConfigState): Int {
        val isTwoPassMode = config.mode == ProcessingMode.DISCORD_20MB ||
            config.mode == ProcessingMode.WHATSAPP_100MB ||
            config.mode == ProcessingMode.GMAIL_25MB ||
            config.mode == ProcessingMode.CUSTOM_SIZE

        if (isTwoPassMode) {
            val targetMb = when (config.mode) {
                ProcessingMode.DISCORD_20MB -> 20.0
                ProcessingMode.WHATSAPP_100MB -> 100.0
                ProcessingMode.GMAIL_25MB -> 25.0
                else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 20.0
            }
            val dur = media.durationSec ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput) ?: 1.0
            val defAb = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb)
            val ab = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
            val twoPassKbps = FfmpegMath.calculateTwoPassVideoBitrateKbps(targetMb, dur, ab)
            // Hardware 1-pass ABR fluctuates slightly more than 2-pass; apply a gentle 4% safety trim
            val turboKbps = (twoPassKbps * 0.96).roundToInt().coerceAtLeast(100)
            return turboKbps * 1000
        }

        // For CRF / re-encode / precise trim modes, estimate a high-quality hardware VBR bitrate
        // scaled to target resolution, frame rate, and codec efficiency.
        val (outW, outH) = resolveTargetDimensions(media, config)
        val pixels = (outW.toLong() * outH.toLong()).coerceAtLeast(640L * 360L)
        val reference1080pPixels = 1920L * 1080L
        val pixelScale = (pixels.toDouble() / reference1080pPixels.toDouble()).coerceIn(0.22, 4.2)

        val effectiveFps = when {
            config.fps == FpsOption.FPS_30 -> 30.0
            media.fps != null && media.fps > 1.0 -> media.fps.coerceIn(15.0, 120.0)
            else -> 30.0
        }
        val fpsScale = (effectiveFps / 30.0).coerceIn(0.85, 1.85)
        val base1080p30Bps = if (config.codec == VideoCodecOption.H265) 6_500_000.0 else 9_500_000.0
        val estimated = (base1080p30Bps * pixelScale * fpsScale).roundToInt()
        return estimated.coerceIn(1_200_000, 42_000_000)
    }

    /**
     * Resolves exact even output dimensions matching the app's 3-way aspect ratio protection.
     */
    fun resolveTargetDimensions(media: MediaInfo, config: ModeConfigState): Pair<Int, Int> {
        val srcW = (media.width ?: 1920).coerceAtLeast(2)
        val srcH = (media.height ?: 1080).coerceAtLeast(2)
        return resolveTargetDimensionsForDisplay(srcW, srcH, config)
    }

    private fun resolveTargetDimensionsForDisplay(
        dispW: Int,
        dispH: Int,
        config: ModeConfigState
    ): Pair<Int, Int> {
        val supportsScaling = config.mode == ProcessingMode.DISCORD_20MB ||
            config.mode == ProcessingMode.WHATSAPP_100MB ||
            config.mode == ProcessingMode.GMAIL_25MB ||
            config.mode == ProcessingMode.CUSTOM_SIZE ||
            (config.mode == ProcessingMode.TRIM_VIDEO &&
                (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY || config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC))

        if (!supportsScaling || config.resolution == ResolutionOption.ORIGINAL) {
            return makeEven(dispW) to makeEven(dispH)
        }

        val targetShort = config.resolution.targetShort ?: return makeEven(dispW) to makeEven(dispH)
        val maxLong = config.resolution.maxLong ?: return makeEven(dispW) to makeEven(dispH)

        return when {
            dispH > dispW -> {
                val outW = makeEven(targetShort)
                val outH = makeEven((dispH.toDouble() * outW.toDouble() / dispW.toDouble()).roundToInt())
                outW to outH
            }
            dispW.toLong() * 9L > dispH.toLong() * 16L -> {
                val outW = makeEven(maxLong)
                val outH = makeEven((dispH.toDouble() * outW.toDouble() / dispW.toDouble()).roundToInt())
                outW to outH
            }
            else -> {
                val outH = makeEven(targetShort)
                val outW = makeEven((dispW.toDouble() * outH.toDouble() / dispH.toDouble()).roundToInt())
                outW to outH
            }
        }
    }

    fun buildTurboOutputFileName(baseFileName: String): String {
        val dotIdx = baseFileName.lastIndexOf('.')
        val stem = if (dotIdx > 0) baseFileName.substring(0, dotIdx) else baseFileName
        return "${stem}_Turbo.mp4"
    }

    /**
     * Runs hardware-accelerated Media3 transcoding. If aggressive OEM hardware performance tuning
     * (`operatingRate` / `VBR`) is rejected during encoder init on a specific SoC HAL, automatically
     * retries once with Media3's built-in safe encoder profile before surfacing an error.
     */
    suspend fun transcode(
        context: Context,
        media: MediaInfo,
        config: ModeConfigState,
        outputFile: File,
        inputUriOverride: Uri? = null,
        onProgress: (percent: Int, elapsedSec: Double) -> Unit
    ): Media3TurboResult {
        val sourceUri = inputUriOverride ?: media.uri
        val displayDims = readDisplayDimensions(context, sourceUri, media)
        val firstAttempt = executeSingleAttempt(
            context = context,
            media = media,
            sourceUri = sourceUri,
            displayDims = displayDims,
            config = config,
            outputFile = outputFile,
            useAggressiveHwBoost = true,
            onProgress = onProgress
        )

        val shouldRetryWithSafeEncoderSettings = !firstAttempt.success &&
            !firstAttempt.cancelled &&
            firstAttempt.errorCodeName in setOf(
                "ERROR_CODE_ENCODER_INIT_FAILED",
                "ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED",
                "ERROR_CODE_ENCODING_FAILED"
            )

        if (!shouldRetryWithSafeEncoderSettings) {
            return firstAttempt
        }

        // Clean partial file before retrying with Media3's safe SoC-default encoder parameters
        runCatching { if (outputFile.exists()) outputFile.delete() }
        return executeSingleAttempt(
            context = context,
            media = media,
            sourceUri = sourceUri,
            displayDims = displayDims,
            config = config,
            outputFile = outputFile,
            useAggressiveHwBoost = false,
            onProgress = onProgress
        )
    }

    private suspend fun executeSingleAttempt(
        context: Context,
        media: MediaInfo,
        sourceUri: Uri,
        displayDims: Pair<Int, Int>?,
        config: ModeConfigState,
        outputFile: File,
        useAggressiveHwBoost: Boolean,
        onProgress: (percent: Int, elapsedSec: Double) -> Unit
    ): Media3TurboResult = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        val cancelledFlag = AtomicBoolean(false)
        val startUptimeMs = SystemClock.elapsedRealtime()

        fun completeOnce(result: Media3TurboResult) {
            if (finished.compareAndSet(false, true)) {
                activeCancelAction.set(null)
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
        }

        val startRunnable = Runnable {
            if (finished.get() || cancelledFlag.get()) {
                completeOnce(Media3TurboResult(success = false, cancelled = true))
                return@Runnable
            }

            try {
                // 1. Build MediaItem (with ClippingConfiguration if trimming)
                val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
                if (config.mode == ProcessingMode.TRIM_VIDEO) {
                    val (startStr, endStr, _) = FfmpegMath.resolveTrimRange(config, media.durationSec)
                    val startMs = startStr?.let { FfmpegMath.parseTimestampToSeconds(it) }
                        ?.coerceAtLeast(0.0)
                        ?.let { (it * 1000.0).toLong() }
                        ?: 0L
                    val endMs = endStr?.let { FfmpegMath.parseTimestampToSeconds(it) }
                        ?.coerceAtLeast(0.0)
                        ?.let { (it * 1000.0).toLong() }

                    val clipBuilder = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                    if (endMs != null && endMs > startMs) {
                        clipBuilder.setEndPositionMs(endMs)
                    }
                    mediaItemBuilder.setClippingConfiguration(clipBuilder.build())
                }
                val mediaItem = mediaItemBuilder.build()

                // 2. Build zero-copy OpenGL video effects only when strictly needed
                // (leaving videoEffects empty lets Media3 run the fastest direct Surface HW path)
                val videoEffects = mutableListOf<Effect>()
                val supportsScalingAndFps = config.mode == ProcessingMode.DISCORD_20MB ||
                    config.mode == ProcessingMode.WHATSAPP_100MB ||
                    config.mode == ProcessingMode.GMAIL_25MB ||
                    config.mode == ProcessingMode.CUSTOM_SIZE ||
                    (config.mode == ProcessingMode.TRIM_VIDEO &&
                        (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY || config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC))

                val needsFpsDrop = supportsScalingAndFps &&
                    config.fps == FpsOption.FPS_30 &&
                    (media.fps == null || media.fps > 30.5)
                if (needsFpsDrop) {
                    videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
                }

                val wantsResize = supportsScalingAndFps && config.resolution != ResolutionOption.ORIGINAL
                if (wantsResize && config.resolution.targetShort != null) {
                    val targetShort = config.resolution.targetShort
                    if (displayDims != null) {
                        val (dispW, dispH) = displayDims
                        val isUltrawide = dispW.toLong() * 9L > dispH.toLong() * 16L
                        val srcShort = minOf(dispW, dispH)
                        val srcLong = maxOf(dispW, dispH)
                        val maxLong = config.resolution.maxLong ?: (targetShort * 16 / 9)

                        if (isUltrawide && srcLong > maxLong) {
                            // CinemaScope 2.39:1: cap long edge using true post-rotation display dims
                            val (outW, outH) = resolveTargetDimensionsForDisplay(dispW, dispH, config)
                            videoEffects.add(
                                Presentation.createForWidthAndHeight(
                                    outW,
                                    outH,
                                    Presentation.LAYOUT_SCALE_TO_FIT
                                )
                            )
                        } else if (srcShort > targetShort) {
                            // Standard & Portrait (including 90°/270° rotated phone videos):
                            // createForShortSide preserves exact orientation with ZERO black bars!
                            videoEffects.add(Presentation.createForShortSide(targetShort))
                        }
                    } else {
                        videoEffects.add(Presentation.createForShortSide(targetShort))
                    }
                } else if (media.needsEvenScale && displayDims != null) {
                    videoEffects.add(
                        Presentation.createForWidthAndHeight(
                            makeEven(displayDims.first),
                            makeEven(displayDims.second),
                            Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                }

                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(!media.hasAudio)
                    .setEffects(if (videoEffects.isEmpty()) Effects.EMPTY else Effects(emptyList(), videoEffects))
                    .build()

                // 3. Configure Composition with HDR / 8-bit / 10-bit handling
                val hdrMode = if (config.codec == VideoCodecOption.H265 && config.bitDepth == BitDepthOption.BIT_10) {
                    Composition.HDR_MODE_KEEP_HDR
                } else {
                    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
                }
                val composition = Composition.Builder(
                    EditedMediaItemSequence.Builder(editedMediaItem).build()
                )
                    .setHdrMode(hdrMode)
                    .build()

                // 4. Configure MediaCodec Hardware Video Encoder for maximum throughput
                val videoMime = when (config.codec) {
                    VideoCodecOption.H264 -> MimeTypes.VIDEO_H264
                    VideoCodecOption.H265 -> MimeTypes.VIDEO_H265
                }
                val targetBitrateBps = resolveTargetVideoBitrateBps(media, config)
                val settingsBuilder = VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrateBps)

                if (useAggressiveHwBoost) {
                    if (supportsHardwareVbr(videoMime)) {
                        settingsBuilder.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                    // High operating rate + non-realtime offline priority maximizes Qualcomm/MediaTek/Exynos DSP throughput
                    settingsBuilder.setEncoderPerformanceParameters(
                        /* operatingRate= */ Short.MAX_VALUE.toInt(),
                        /* priority= */ 1
                    )
                }

                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(settingsBuilder.build())
                    .setEnableFallback(true)
                    .build()

                val progressHolder = ProgressHolder()
                var progressPoller: Runnable? = null

                fun stopPolling() {
                    progressPoller?.let { mainHandler.removeCallbacks(it) }
                    progressPoller = null
                }

                val transformerBuilder = Transformer.Builder(context)
                    .setVideoMimeType(videoMime)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            stopPolling()
                            val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            onProgress(100, elapsedSec)
                            completeOnce(
                                Media3TurboResult(
                                    success = true,
                                    elapsedSec = elapsedSec
                                )
                            )
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            stopPolling()
                            val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            if (cancelledFlag.get()) {
                                completeOnce(
                                    Media3TurboResult(
                                        success = false,
                                        cancelled = true,
                                        elapsedSec = elapsedSec
                                    )
                                )
                            } else {
                                completeOnce(
                                    Media3TurboResult(
                                        success = false,
                                        cancelled = false,
                                        isUnsupportedFormat = isUnsupportedFormatException(exportException),
                                        errorCodeName = exportException.errorCodeName,
                                        errorDetail = exportException.message ?: exportException.cause?.message,
                                        elapsedSec = elapsedSec
                                    )
                                )
                            }
                        }
                    })

                // Smart audio pass-through: if user asked for audio stream copy and source is already AAC,
                // skip forced audio transcoding so Media3 muxes the AAC stream at 0% CPU cost!
                val wantsAudioCopy = config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
                    (config.mode == ProcessingMode.TRIM_VIDEO && config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY)
                val sourceIsAac = media.audioCodec?.lowercase(Locale.US)?.contains("aac") == true
                if (media.hasAudio && !(wantsAudioCopy && sourceIsAac)) {
                    transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                }

                val transformer = transformerBuilder.build()

                val cancelBlock: () -> Unit = {
                    if (cancelledFlag.compareAndSet(false, true)) {
                        stopPolling()
                        runCatching { transformer.cancel() }
                        val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                        completeOnce(
                            Media3TurboResult(
                                success = false,
                                cancelled = true,
                                elapsedSec = elapsedSec
                            )
                        )
                    }
                }
                activeCancelAction.set(cancelBlock)

                // Poll progress smoothly (~12.5 Hz -> interpolated at 120Hz by Compose spring needle)
                progressPoller = object : Runnable {
                    override fun run() {
                        if (finished.get() || cancelledFlag.get()) return
                        val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                        val state = runCatching { transformer.getProgress(progressHolder) }
                            .getOrDefault(Transformer.PROGRESS_STATE_UNAVAILABLE)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 99), elapsedSec)
                        } else {
                            onProgress(-1, elapsedSec)
                        }
                        if (!finished.get() && !cancelledFlag.get()) {
                            mainHandler.postDelayed(this, 80L)
                        }
                    }
                }

                transformer.start(composition, outputFile.absolutePath)
                mainHandler.postDelayed(progressPoller!!, 60L)
            } catch (t: Throwable) {
                val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                val isUnsupported = t is ExportException && isUnsupportedFormatException(t) ||
                    t.javaClass.simpleName.contains("Unsupported", ignoreCase = true) ||
                    t.message?.contains("unsupported", ignoreCase = true) == true ||
                    t.message?.contains("UnrecognizedInputFormat", ignoreCase = true) == true
                completeOnce(
                    Media3TurboResult(
                        success = false,
                        cancelled = cancelledFlag.get(),
                        isUnsupportedFormat = isUnsupported,
                        errorCodeName = (t as? ExportException)?.errorCodeName,
                        errorDetail = t.message,
                        elapsedSec = elapsedSec
                    )
                )
            }
        }

        cont.invokeOnCancellation {
            cancelActive()
        }

        mainHandler.post(startRunnable)
    }

    /**
     * Reads true display width & height accounting for 90°/270° rotation metadata so rotated
     * portrait smartphone videos are never squashed or letterboxed during scaling.
     */
    private fun readDisplayDimensions(
        context: Context,
        uri: Uri,
        fallbackMedia: MediaInfo
    ): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w != null && h != null && w > 0 && h > 0) {
                if (rot == 90 || rot == 270) (h to w) else (w to h)
            } else if (fallbackMedia.width != null && fallbackMedia.height != null) {
                fallbackMedia.width to fallbackMedia.height
            } else {
                null
            }
        } catch (_: Throwable) {
            if (fallbackMedia.width != null && fallbackMedia.height != null) {
                fallbackMedia.width to fallbackMedia.height
            } else {
                null
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Checks whether the device has a hardware encoder for [mimeType] that explicitly supports VBR.
     */
    private fun supportsHardwareVbr(mimeType: String): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                if (!info.isEncoder) return@any false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) return@any false
                if (!info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) return@any false
                val caps = info.getCapabilitiesForType(mimeType)?.encoderCapabilities ?: return@any false
                caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }.getOrDefault(false)
    }

    private fun isUnsupportedFormatException(ex: ExportException): Boolean {
        return when (ex.errorCode) {
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ExportException.ERROR_CODE_UNSPECIFIED -> true
            else -> {
                val msg = buildString {
                    append(ex.errorCodeName)
                    append(' ')
                    append(ex.message.orEmpty())
                    append(' ')
                    append(ex.cause?.javaClass?.simpleName.orEmpty())
                    append(' ')
                    append(ex.cause?.message.orEmpty())
                }
                msg.contains("Unsupported", ignoreCase = true) ||
                    msg.contains("Unrecognized", ignoreCase = true) ||
                    msg.contains("Extractor", ignoreCase = true) ||
                    msg.contains("Codec", ignoreCase = true) ||
                    msg.contains("Format", ignoreCase = true) ||
                    msg.contains("Parser", ignoreCase = true)
            }
        }
    }

    private fun makeEven(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else safe - 1
    }
}
