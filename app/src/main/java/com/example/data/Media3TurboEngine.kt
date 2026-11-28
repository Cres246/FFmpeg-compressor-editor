package com.example.data

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultCodec
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
import com.example.domain.SubtitleEmbedMode
import com.example.domain.TrimSubMode
import com.example.domain.VideoCodecOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

data class Media3TurboResult(
    val success: Boolean,
    val cancelled: Boolean = false,
    val isUnsupportedFormat: Boolean = false,
    val errorCodeName: String? = null,
    val errorDetail: String? = null,
    val elapsedSec: Double = 0.0,
    val outputHasAudio: Boolean? = null,
    val outputIs10Bit: Boolean? = null
)

data class TurboBitratePlan(
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val effectiveDurationSec: Double,
    val useCbr: Boolean,
    val forceAudioReencode: Boolean,
    val sourceIsAac: Boolean,
    val isTargetSizeMode: Boolean,
    val resolvedDisplayDims: Pair<Int, Int>? = null,
    val stressTier: Int = 0
)

internal data class HardwareVideoEncoderCaps(
    val codecName: String?,
    val minBitrateBps: Int,
    val maxBitrateBps: Int,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val supportsAvcHighLevel52: Boolean,
    val supportsHevc10Bit: Boolean,
    val videoCapabilities: MediaCodecInfo.VideoCapabilities?,
    val hdrCodecName: String? = null,
    val supportsHdrEditing: Boolean = false,
    val supportsAbgr2101010: Boolean = false,
    val highestHevc10Level: Int = -1
) {
    fun maxFpsForSize(width: Int, height: Int): Double? {
        val vc = videoCapabilities ?: return null
        return runCatching {
            when {
                vc.isSizeSupported(width, height) -> vc.getSupportedFrameRatesFor(width, height).upper
                vc.isSizeSupported(height, width) -> vc.getSupportedFrameRatesFor(height, width).upper
                else -> null
            }
        }.getOrNull()
    }

    fun areSizeAndRateSupported(width: Int, height: Int, fps: Double): Boolean {
        val vc = videoCapabilities ?: return true
        return runCatching {
            vc.areSizeAndRateSupported(width, height, fps) ||
                vc.areSizeAndRateSupported(height, width, fps)
        }.getOrDefault(true)
    }
}

internal data class HardwareAudioEncoderCaps(
    val minBitrateBps: Int,
    val maxBitrateBps: Int,
    val maxChannelCount: Int,
    val minSampleRateHz: Int,
    val maxSampleRateHz: Int
) {
    fun clampBitrateForChannels(requestedBps: Int, channelCount: Int): Int {
        val safeChannels = channelCount.coerceIn(1, maxChannelCount.coerceAtLeast(2))
        // Android's platform AAC encoder reliably supports up to ~160 kbps per channel
        // (320 kbps for stereo, up to maxBitrateBps for 5.1 surround) without init failure.
        val perChannelCeiling = (safeChannels * 160_000).coerceAtLeast(160_000)
        val upper = minOf(maxBitrateBps, perChannelCeiling).coerceAtLeast(minBitrateBps)
        return requestedBps.coerceIn(minBitrateBps, upper)
    }

    fun clampSampleRate(sampleRateHz: Int): Int {
        if (sampleRateHz <= 0) return 48_000
        return sampleRateHz.coerceIn(minSampleRateHz, maxSampleRateHz)
    }
}

internal data class SourceTrackProbe(
    val displayDims: Pair<Int, Int>?,
    val durationSec: Double?,
    val sourceTotalBitrateBps: Int?,
    val sourceAudioBitrateBps: Int?,
    val sourceAudioMime: String?,
    val sourceAudioChannels: Int?,
    val sourceAudioSampleRate: Int?,
    val sourceVideoFps: Double?,
    val sourceIsHdr: Boolean = false
)

private enum class EncoderAttemptMode {
    /** Fast hardware pipeline with CBR (target size) / VBR (CRF), 2s GOP, and chip-validated operatingRate. */
    OPTIMIZED_HW,
    /**
     * Minimal raw-style MediaCodec format (no operating-rate, no forced profile/level/bitrate-mode)
     * plus universal OpenGL HDR->SDR tone-mapping fallback for strict Qualcomm/Exynos/MTK HALs,
     * FFmpeg lossless-cut clips, and HDR10/HLG/Dolby Vision phone recordings.
     */
    CLEAN_MINIMAL_HW,
    /** Safe 30 FPS fallback if a 4K60 / 2.7K60 / 1080p120 stream exceeds the chip's VPU rate ceiling. */
    SAFE_FPS_FALLBACK
}

/**
 * Native Jetpack Media3 Transformer hardware-accelerated transcoding engine.
 * - Never instantiated or loaded unless the user explicitly activates "Acelem Var" (Hardware Turbo).
 * - Uses Android MediaCodec hardware decoders/encoders + Zero-Copy GPU Surface pipeline.
 * - Strictly honors user-selected settings (audio bitrate, codec, resolution, FPS) while clamping
 *   only impossible hardware encoder ceilings so videos never crash on strict OEM C2 drivers.
 * - Recalculates strict target-size bitrate using hardware CBR (when supported), real audio bitrate
 *   accounting (preventing AAC transmux size leaks), and 2s keyframe interval.
 * - Automatically falls back across 3 hardware tiers (including OpenGL HDR->SDR tone-mapping and
 *   portrait/alignment negotiation) for maximum format & device compatibility.
 */
@OptIn(UnstableApi::class)
object Media3TurboEngine {

    private val activeCancelAction = AtomicReference<(() -> Unit)?>(null)
    private val videoCapsCache = ConcurrentHashMap<String, HardwareVideoEncoderCaps>()

    @Volatile
    private var cachedAacCaps: HardwareAudioEncoderCaps? = null

    fun cancelActive() {
        val action = activeCancelAction.getAndSet(null) ?: return
        runCatching { action() }
    }

    fun isTargetSizeMode(mode: ProcessingMode): Boolean =
        mode == ProcessingMode.DISCORD_20MB ||
            mode == ProcessingMode.WHATSAPP_100MB ||
            mode == ProcessingMode.GMAIL_25MB ||
            mode == ProcessingMode.CUSTOM_SIZE

    fun resolveTargetMb(config: ModeConfigState): Double? = when (config.mode) {
        ProcessingMode.DISCORD_20MB -> 20.0
        ProcessingMode.WHATSAPP_100MB -> 100.0
        ProcessingMode.GMAIL_25MB -> 25.0
        ProcessingMode.CUSTOM_SIZE -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
        else -> null
    }

    /**
     * Zero-IPC synchronous bitrate estimate safe to call on the Main Thread during the slide
     * transition before `transcode()` resolves exact hardware capabilities on Dispatchers.IO.
     */
    fun estimateInitialVideoBitrateBps(media: MediaInfo, config: ModeConfigState): Int {
        val videoMime = when (config.codec) {
            VideoCodecOption.H264 -> MimeTypes.VIDEO_H264
            VideoCodecOption.H265 -> MimeTypes.VIDEO_H265
        }
        val cachedVideoCaps = videoCapsCache[videoMime] ?: HardwareVideoEncoderCaps(
            codecName = null,
            minBitrateBps = 100_000,
            maxBitrateBps = 32_000_000,
            supportsCbr = true,
            supportsVbr = true,
            supportsAvcHighLevel52 = false,
            supportsHevc10Bit = false,
            videoCapabilities = null
        )
        val cachedAudio = cachedAacCaps ?: defaultAacCaps()
        val fallbackProbe = SourceTrackProbe(
            displayDims = if (media.width != null && media.height != null) media.width to media.height else null,
            durationSec = media.durationSec,
            sourceTotalBitrateBps = if (media.sizeBytes > 0L && media.durationSec != null && media.durationSec > 0.5) {
                ((media.sizeBytes * 8.0) / media.durationSec).roundToInt()
            } else null,
            sourceAudioBitrateBps = media.audioBitrateKbps?.times(1000),
            sourceAudioMime = null,
            sourceAudioChannels = media.audioChannels ?: 2,
            sourceAudioSampleRate = 48_000,
            sourceVideoFps = media.fps
        )
        return computeTurboBitratePlan(media, config, fallbackProbe, cachedVideoCaps, cachedAudio).videoBitrateBps
    }

    /**
     * Dedicated Turbo bitrate recalculation ensuring target-size outputs stay strictly under the
     * requested MB limit (e.g. < 20 MB, < 25 MB, < 100 MB) without wasting unnecessary margin,
     * strictly honoring the user's chosen audio bitrate while staying inside hardware encoder ceilings.
     */
    internal fun computeTurboBitratePlan(
        media: MediaInfo,
        config: ModeConfigState,
        probe: SourceTrackProbe,
        hwCaps: HardwareVideoEncoderCaps,
        aacCaps: HardwareAudioEncoderCaps
    ): TurboBitratePlan {
        val baseDuration = probe.durationSec
            ?: media.durationSec
            ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput)
            ?: 1.0

        val effectiveDurationSec = if (config.mode == ProcessingMode.TRIM_VIDEO) {
            FfmpegMath.resolveTrimRange(config, baseDuration).third?.takeIf { it > 0.1 } ?: baseDuration
        } else {
            baseDuration
        }.coerceAtLeast(0.5)

        val targetMb = resolveTargetMb(config)
        val isTargetMode = targetMb != null

        val sourceIsAac = probe.sourceAudioMime.equals(MimeTypes.AUDIO_AAC, ignoreCase = true) ||
            media.audioCodec?.lowercase(Locale.US)?.contains("aac") == true
        val channels = probe.sourceAudioChannels?.coerceAtLeast(1) ?: 2

        if (isTargetMode && targetMb != null) {
            val defAb = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb)
            val rawReqAudioKbps = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
            val safeReqAudioBps = if (!media.hasAudio) {
                0
            } else {
                aacCaps.clampBitrateForChannels(rawReqAudioKbps * 1000, channels)
            }

            // Copy AAC directly ONLY when source AAC is already at or below the user's requested bitrate
            // (zero CPU cost, zero extra MB surprise). Otherwise encode at the user's exact bitrate.
            val canPassThroughAac = media.hasAudio &&
                sourceIsAac &&
                probe.sourceAudioBitrateBps != null &&
                probe.sourceAudioBitrateBps in 16_000..(safeReqAudioBps + 12_000)

            val forceAudioReencode = media.hasAudio && !canPassThroughAac
            val effectiveAudioBps = when {
                !media.hasAudio -> 0
                canPassThroughAac -> probe.sourceAudioBitrateBps!!
                else -> safeReqAudioBps
            }

            // Strict decimal MB target (20 MB = 20,000,000 bytes = 19.07 MiB) satisfies Discord, Gmail,
            // WhatsApp, and Android file managers without ever crossing the MB threshold.
            val strictTargetBytes = targetMb * 1_000_000.0
            val muxOverheadBytes = strictTargetBytes * 0.018 + 64_000.0

            // Evaluate bitrate stress against actual encoded resolution & FPS so ONLY high-res
            // ignored-warning or extreme low-bitrate encodes receive 1–3 extra margin notches.
            val (outW, outH) = if (probe.displayDims != null) {
                resolveTargetDimensionsForDisplay(probe.displayDims.first, probe.displayDims.second, config)
            } else {
                resolveTargetDimensions(media, config)
            }
            val rawSrcFps = (probe.sourceVideoFps ?: media.fps ?: 30.0).coerceIn(15.0, 120.0)
            val effFps = if (config.fps == FpsOption.FPS_30) minOf(rawSrcFps, 30.0) else rawSrcFps
            val baseEstimatedKbps = FfmpegMath.calculateTwoPassVideoBitrateKbps(
                targetMb = targetMb,
                durationSec = effectiveDurationSec,
                audioBitrateKbps = (effectiveAudioBps / 1000).coerceAtLeast(0)
            )
            val stress = FfmpegMath.assessTargetSizeBitrateStress(
                baseVideoBitrateKbps = baseEstimatedKbps,
                codec = config.codec,
                durationSec = effectiveDurationSec,
                outputWidth = outW,
                outputHeight = outH,
                outputFps = effFps
            )

            // In extreme starvation (Tier 3) where audio alone chokes video below 120 kbps,
            // take initiative to trim audio share so video + audio physically fits under target MB.
            val totalBudgetBps = ((strictTargetBytes - muxOverheadBytes) * 8.0 / effectiveDurationSec)
                .coerceAtLeast(0.0)
            val finalAudioBps = if (
                stress.tier >= 3 &&
                media.hasAudio &&
                effectiveAudioBps > 32_000 &&
                (totalBudgetBps - effectiveAudioBps) < 120_000.0
            ) {
                val maxAudioShareBps = (totalBudgetBps * 0.30).toInt().coerceIn(32_000, effectiveAudioBps)
                aacCaps.clampBitrateForChannels(minOf(effectiveAudioBps, maxAudioShareBps), channels)
            } else {
                effectiveAudioBps
            }
            val finalForceAudioReencode = forceAudioReencode || (media.hasAudio && finalAudioBps < effectiveAudioBps)

            val audioBytes = (finalAudioBps / 8.0) * effectiveDurationSec
            val baseAvailableVideoBytes = (strictTargetBytes - muxOverheadBytes - audioBytes)
                .coerceAtLeast(effectiveDurationSec * 8_000.0)
            val availableVideoBytes = if (stress.tier >= 2) {
                baseAvailableVideoBytes
            } else {
                baseAvailableVideoBytes.coerceAtLeast(effectiveDurationSec * 12_500.0)
            }

            // Hardware 1-pass CBR holds tightly within ~2-3%; VBR overshoots more on complex scenes.
            // Tiny 1-2 MB targets and starved high-res/extreme-low-bitrate scenarios get targeted
            // extra headroom without changing normal > 2 MB calculations.
            val useCbr = hwCaps.supportsCbr
            val baseMargin = if (useCbr) 0.955 else 0.885
            val smallTargetNotch = when {
                targetMb <= 1.0 + 1e-6 -> 0.025
                targetMb <= 2.0 + 1e-6 -> 0.015
                else -> 0.0
            }
            val hwRateControlMargin = (baseMargin - smallTargetNotch - stress.turboExtraMarginDrop)
                .coerceAtLeast(0.72)
            val rawVideoBps = ((availableVideoBytes * hwRateControlMargin * 8.0) / effectiveDurationSec).roundToInt()

            // Protect Qualcomm/Exynos/MTK encoders (e.g. AVC Level 5.1) from extreme bitrates on short clips
            val codecHardCeiling = if (config.codec == VideoCodecOption.H264) 28_000_000 else 34_000_000
            val hwCeiling = minOf(hwCaps.maxBitrateBps, codecHardCeiling).coerceAtLeast(2_000_000)
            val shortClipCap = probe.sourceTotalBitrateBps
                ?.takeIf { it > 400_000 }
                ?.let { maxOf((it * 1.15).roundToInt(), 16_000_000) }
                ?: hwCeiling

            val minVideoFloorBps = if (stress.tier >= 2) 64_000 else 100_000
            val finalVideoBps = rawVideoBps.coerceIn(
                maxOf(minVideoFloorBps, hwCaps.minBitrateBps),
                minOf(hwCeiling, shortClipCap)
            )

            return TurboBitratePlan(
                videoBitrateBps = finalVideoBps,
                audioBitrateBps = finalAudioBps,
                effectiveDurationSec = effectiveDurationSec,
                useCbr = useCbr,
                forceAudioReencode = finalForceAudioReencode,
                sourceIsAac = sourceIsAac,
                isTargetSizeMode = true,
                resolvedDisplayDims = probe.displayDims,
                stressTier = stress.tier
            )
        }

        // CRF / Exact Trim modes: crisp hardware VBR bitrate scaled to resolution, FPS, and codec
        val (outW, outH) = if (probe.displayDims != null) {
            resolveTargetDimensionsForDisplay(probe.displayDims.first, probe.displayDims.second, config)
        } else {
            resolveTargetDimensions(media, config)
        }
        val pixels = (outW.toLong() * outH.toLong()).coerceAtLeast(640L * 360L)
        val reference1080pPixels = 1920L * 1080L
        val pixelScale = (pixels.toDouble() / reference1080pPixels.toDouble()).coerceIn(0.22, 4.0)

        val effectiveFps = when {
            config.fps == FpsOption.FPS_30 -> 30.0
            probe.sourceVideoFps != null && probe.sourceVideoFps > 1.0 -> probe.sourceVideoFps.coerceIn(15.0, 120.0)
            media.fps != null && media.fps > 1.0 -> media.fps.coerceIn(15.0, 120.0)
            else -> 30.0
        }
        val fpsScale = (effectiveFps / 30.0).coerceIn(0.85, 1.75)
        val base1080p30Bps = if (config.codec == VideoCodecOption.H265) 6_000_000.0 else 8_500_000.0
        val estimated = (base1080p30Bps * pixelScale * fpsScale).roundToInt()
        val codecHardCeiling = if (config.codec == VideoCodecOption.H264) 26_000_000 else 32_000_000
        val hwCeiling = minOf(hwCaps.maxBitrateBps, codecHardCeiling).coerceAtLeast(2_000_000)

        val wantsAudioCopy = config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
            config.mode == ProcessingMode.EMBED_SUBTITLES ||
            (config.mode == ProcessingMode.TRIM_VIDEO && config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY)
        val defaultAb = if (config.mode == ProcessingMode.TRIM_VIDEO) 192 else 256
        val rawReqAudioBps = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defaultAb) * 1000
        val safeReqAudioBps = if (!media.hasAudio) 0 else aacCaps.clampBitrateForChannels(rawReqAudioBps, channels)

        // If user explicitly asked for AAC re-encode (CRF + AAC) and source AAC differs noticeably,
        // honor their target audio bitrate; if it already matches closely, pass-through at 0 CPU cost.
        val sourceMatchesRequestedAac = sourceIsAac &&
            probe.sourceAudioBitrateBps != null &&
            abs(probe.sourceAudioBitrateBps - safeReqAudioBps) <= 16_000
        val forceAudioReencode = media.hasAudio && !wantsAudioCopy && !sourceMatchesRequestedAac

        return TurboBitratePlan(
            videoBitrateBps = estimated.coerceIn(maxOf(1_200_000, hwCaps.minBitrateBps), hwCeiling),
            audioBitrateBps = safeReqAudioBps,
            effectiveDurationSec = effectiveDurationSec,
            useCbr = false,
            forceAudioReencode = forceAudioReencode,
            sourceIsAac = sourceIsAac,
            isTargetSizeMode = false,
            resolvedDisplayDims = probe.displayDims
        )
    }

    /**
     * Resolves exact even output dimensions matching the app's 3-way aspect ratio protection.
     */
    fun resolveTargetDimensions(
        media: MediaInfo,
        config: ModeConfigState,
        displayDimsOverride: Pair<Int, Int>? = null
    ): Pair<Int, Int> {
        val srcW = (displayDimsOverride?.first ?: media.width ?: 1920).coerceAtLeast(2)
        val srcH = (displayDimsOverride?.second ?: media.height ?: 1080).coerceAtLeast(2)
        return resolveTargetDimensionsForDisplay(srcW, srcH, config)
    }

    private fun resolveTargetDimensionsForDisplay(
        dispW: Int,
        dispH: Int,
        config: ModeConfigState
    ): Pair<Int, Int> {
        val supportsScaling = isTargetSizeMode(config.mode) ||
            (config.mode == ProcessingMode.TRIM_VIDEO &&
                (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY || config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC))

        if (!supportsScaling || config.resolution == ResolutionOption.ORIGINAL) {
            return makeEven(dispW) to makeEven(dispH)
        }

        val targetShort = config.resolution.targetShort ?: return makeEven(dispW) to makeEven(dispH)
        val maxLong = config.resolution.maxLong ?: return makeEven(dispW) to makeEven(dispH)

        return when {
            dispH > dispW -> {
                if (dispW <= targetShort) return makeEven(dispW) to makeEven(dispH)
                val outW = makeEven(targetShort)
                val outH = makeEven((dispH.toDouble() * outW.toDouble() / dispW.toDouble()).roundToInt())
                outW to outH
            }
            dispW.toLong() * 9L > dispH.toLong() * 16L -> {
                if (dispW <= maxLong) return makeEven(dispW) to makeEven(dispH)
                val outW = makeEven(maxLong)
                val outH = makeEven((dispH.toDouble() * outW.toDouble() / dispW.toDouble()).roundToInt())
                outW to outH
            }
            else -> {
                if (dispH <= targetShort) return makeEven(dispW) to makeEven(dispH)
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
     * Runs hardware-accelerated Media3 transcoding with automatic 3-tier hardware compatibility
     * recovery so lossless-cut clips, HDR/HLG phone videos, portrait clips, and 4K60/1080p120
     * streams succeed reliably across Qualcomm, MediaTek, and Exynos chips.
     */
    suspend fun transcode(
        context: Context,
        media: MediaInfo,
        config: ModeConfigState,
        outputFile: File,
        inputUriOverride: Uri? = null,
        onPlanResolved: (TurboBitratePlan) -> Unit = {},
        onProgress: (percent: Int, elapsedSec: Double, effectiveDurationSec: Double) -> Unit
    ): Media3TurboResult {
        val sourceUri = inputUriOverride ?: media.uri
        val videoMime = when (config.codec) {
            VideoCodecOption.H264 -> MimeTypes.VIDEO_H264
            VideoCodecOption.H265 -> MimeTypes.VIDEO_H265
        }
        val hwCaps = queryHardwareVideoEncoderCaps(videoMime)
        val aacCaps = queryAacEncoderCaps()
        val probe = probeSourceTracks(context, sourceUri, media)
        val plan = computeTurboBitratePlan(media, config, probe, hwCaps, aacCaps)
        onPlanResolved(plan)
        // Brief calm settle so the UI displays the exact calculated bitrate/specs cleanly
        // before the hardware decoder/encoder allocates DMA-BUF surfaces.
        delay(120L)

        val wants10BitHevc = config.codec == VideoCodecOption.H265 &&
            config.bitDepth == BitDepthOption.BIT_10
        if (wants10BitHevc && !hwCaps.supportsHevc10Bit) {
            return Media3TurboResult(
                success = false,
                cancelled = false,
                isUnsupportedFormat = false,
                errorCodeName = "ERROR_CODE_10BIT_NOT_PRESERVED",
                errorDetail = "Device hardware HEVC encoder does not support 10-bit Main10 encoding",
                elapsedSec = 0.0,
                outputIs10Bit = false
            )
        }

        suspend fun verifyTenBitSatisfied(attempt: Media3TurboResult): Media3TurboResult {
            if (!attempt.success || !wants10BitHevc) return attempt
            val probed = runCatching { FfmpegEngine.probeEncodedOutput(outputFile) }.getOrNull()
            val actually10Bit = probed?.is10Bit ?: (attempt.outputIs10Bit == true)
            return if (actually10Bit) {
                attempt.copy(
                    outputIs10Bit = true,
                    outputHasAudio = probed?.hasReadableAudioSamples ?: attempt.outputHasAudio
                )
            } else {
                Media3TurboResult(
                    success = false,
                    cancelled = false,
                    isUnsupportedFormat = false,
                    errorCodeName = "ERROR_CODE_10BIT_NOT_PRESERVED",
                    errorDetail = "Hardware encoder silently output ${probed?.pixFmt ?: "8-bit"} instead of 10-bit HEVC",
                    elapsedSec = attempt.elapsedSec,
                    outputIs10Bit = false
                )
            }
        }

        val hasHardsubOverlay = config.mode == ProcessingMode.EMBED_SUBTITLES &&
            config.subtitleEmbedMode == SubtitleEmbedMode.HARDSUB &&
            config.subtitleCues.isNotEmpty()

        // Tier 1: Optimized Media3 hardware pipeline (CBR for target-size, 2s GOP, safe 4K & 10-bit negotiation)
        val rawAttempt1 = executeSingleAttempt(
            context = context,
            media = media,
            sourceUri = sourceUri,
            probe = probe,
            hwCaps = hwCaps,
            aacCaps = aacCaps,
            plan = plan,
            config = config,
            outputFile = outputFile,
            attemptMode = EncoderAttemptMode.OPTIMIZED_HW,
            onProgress = onProgress
        )
        val attempt1 = verifyTenBitSatisfied(rawAttempt1)
        if (attempt1.success || attempt1.cancelled) {
            return attempt1
        }
        if (attempt1.errorCodeName != "ERROR_CODE_10BIT_NOT_PRESERVED" && !isRecoverableAttemptError(attempt1)) {
            return attempt1
        }

        // Wait for Qualcomm/OEM C2 HAL (e.g. vendor.qti.media.c2) to finish asynchronously
        // releasing Attempt 1's 4K VPU session + EGL surface before launching fallback tier.
        onProgress(-1, 0.0, plan.effectiveDurationSec)
        runCatching { if (outputFile.exists()) outputFile.delete() }
        delay(450L)

        // Tier 2: Direct native Android MediaExtractor -> MediaCodec HW Decoder -> OpenGL ES 2.0/3.0 Surface ->
        // MediaCodec HW Encoder -> MediaMuxer. Skipped for Hardsub overlay jobs so subtitles are NEVER dropped.
        val attempt2 = if (!hasHardsubOverlay) {
            val rawAttempt2 = executeDirectAttempt(
                context = context,
                media = media,
                sourceUri = sourceUri,
                probe = probe,
                hwCaps = hwCaps,
                plan = plan,
                config = config,
                videoMime = videoMime,
                outputFile = outputFile,
                useCbr = plan.useCbr && hwCaps.supportsCbr,
                dropTo30Fps = false,
                onProgress = onProgress
            )
            val verified2 = verifyTenBitSatisfied(rawAttempt2)
            if (verified2.success || verified2.cancelled) {
                return verified2
            }
            verified2
        } else {
            attempt1
        }

        val needsOpenGlHdrPipeline = hasHardsubOverlay || attempt2.errorCodeName == DirectMediaCodecTranscoder.ERROR_HDR_NEEDS_OPENGL

        // Tier 3: Try either Media3 OpenGL pipeline (keeping HDR/10-bit if 10-bit was selected!)
        // or Direct MediaCodec 30 FPS VBR fallback (if 4K60 exceeded the VPU rate ceiling).
        val (outW, outH) = if (probe.displayDims != null) {
            resolveTargetDimensionsForDisplay(probe.displayDims.first, probe.displayDims.second, config)
        } else {
            resolveTargetDimensions(media, config)
        }
        val outPixels = outW.toLong() * outH.toLong()
        val srcFps = probe.sourceVideoFps ?: media.fps ?: 30.0
        val canTryFpsFallback = srcFps > 31.0 &&
            config.fps != FpsOption.FPS_30 &&
            (outPixels > 1920L * 1080L || srcFps > 61.0 || !hwCaps.areSizeAndRateSupported(outW, outH, srcFps))

        if (!hasHardsubOverlay) {
            onProgress(-1, 0.0, plan.effectiveDurationSec)
            runCatching { if (outputFile.exists()) outputFile.delete() }
            delay(if (needsOpenGlHdrPipeline) 200L else 450L)
        }

        if (canTryFpsFallback && !needsOpenGlHdrPipeline && !hasHardsubOverlay) {
            val rawAttempt3Direct30 = executeDirectAttempt(
                context = context,
                media = media,
                sourceUri = sourceUri,
                probe = probe,
                hwCaps = hwCaps,
                plan = plan,
                config = config,
                videoMime = videoMime,
                outputFile = outputFile,
                useCbr = false,
                dropTo30Fps = true,
                onProgress = onProgress
            )
            val attempt3Direct30 = verifyTenBitSatisfied(rawAttempt3Direct30)
            if (attempt3Direct30.success || attempt3Direct30.cancelled) {
                return attempt3Direct30
            }
            onProgress(-1, 0.0, plan.effectiveDurationSec)
            runCatching { if (outputFile.exists()) outputFile.delete() }
            delay(400L)
        }

        val rawAttemptMedia3OpenGl = executeSingleAttempt(
            context = context,
            media = media,
            sourceUri = sourceUri,
            probe = probe,
            hwCaps = hwCaps,
            aacCaps = aacCaps,
            plan = plan,
            config = config,
            outputFile = outputFile,
            attemptMode = if (canTryFpsFallback) EncoderAttemptMode.SAFE_FPS_FALLBACK else EncoderAttemptMode.CLEAN_MINIMAL_HW,
            onProgress = onProgress
        )
        val attemptMedia3OpenGl = verifyTenBitSatisfied(rawAttemptMedia3OpenGl)
        if (attemptMedia3OpenGl.success || attemptMedia3OpenGl.cancelled) {
            return attemptMedia3OpenGl
        }
        if (wants10BitHevc && (attempt1.errorCodeName == "ERROR_CODE_10BIT_NOT_PRESERVED" ||
                attempt2.errorCodeName == "ERROR_CODE_10BIT_NOT_PRESERVED" ||
                attemptMedia3OpenGl.errorCodeName == "ERROR_CODE_10BIT_NOT_PRESERVED")
        ) {
            runCatching { if (outputFile.exists()) outputFile.delete() }
            return Media3TurboResult(
                success = false,
                cancelled = false,
                isUnsupportedFormat = false,
                errorCodeName = "ERROR_CODE_10BIT_NOT_PRESERVED",
                errorDetail = attemptMedia3OpenGl.errorDetail ?: attempt1.errorDetail,
                elapsedSec = attemptMedia3OpenGl.elapsedSec,
                outputIs10Bit = false
            )
        }
        return attempt1
    }

    private suspend fun executeDirectAttempt(
        context: Context,
        media: MediaInfo,
        sourceUri: Uri,
        probe: SourceTrackProbe,
        hwCaps: HardwareVideoEncoderCaps,
        plan: TurboBitratePlan,
        config: ModeConfigState,
        videoMime: String,
        outputFile: File,
        useCbr: Boolean,
        dropTo30Fps: Boolean,
        onProgress: (percent: Int, elapsedSec: Double, effectiveDurationSec: Double) -> Unit
    ): Media3TurboResult = withContext(Dispatchers.IO) {
        val cancelFlag = AtomicBoolean(false)
        activeCancelAction.set { cancelFlag.set(true) }
        val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) cancelFlag.set(true)
        }
        try {
            val displayDims = probe.displayDims
            val (outW, outH) = if (displayDims != null) {
                resolveTargetDimensionsForDisplay(displayDims.first, displayDims.second, config)
            } else {
                resolveTargetDimensions(media, config)
            }
            val supportsScalingAndFps = isTargetSizeMode(config.mode) ||
                (config.mode == ProcessingMode.TRIM_VIDEO &&
                    (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY || config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC))
            val srcFps = probe.sourceVideoFps ?: media.fps ?: 30.0
            val wants30Drop = dropTo30Fps ||
                (supportsScalingAndFps && config.fps == FpsOption.FPS_30 && srcFps > 30.5)
            val targetFps = if (wants30Drop) {
                30
            } else {
                val maxChip = hwCaps.maxFpsForSize(outW, outH)
                val bounded = if (maxChip != null && maxChip >= 15.0) minOf(srcFps, maxChip) else srcFps
                bounded.roundToInt().coerceIn(15, 120)
            }

            var clipStartUs: Long? = null
            var clipEndUs: Long? = null
            if (config.mode == ProcessingMode.TRIM_VIDEO) {
                val (startStr, endStr, _) = FfmpegMath.resolveTrimRange(config, probe.durationSec ?: media.durationSec)
                clipStartUs = startStr?.let { FfmpegMath.parseTimestampToSeconds(it) }
                    ?.coerceAtLeast(0.0)
                    ?.let { (it * 1_000_000.0).toLong() }
                clipEndUs = endStr?.let { FfmpegMath.parseTimestampToSeconds(it) }
                    ?.coerceAtLeast(0.0)
                    ?.let { (it * 1_000_000.0).toLong() }
            }

            // If VBR fallback is used in target-size mode, apply the safety margin so
            // hardware VBR stays strictly below the user's MB limit (e.g. <20 MB).
            val vbrFactor = if (plan.stressTier >= 2) 0.90 else 0.93
            val effectiveVideoBps = if (plan.isTargetSizeMode && plan.useCbr && !useCbr) {
                (plan.videoBitrateBps * vbrFactor).roundToInt().coerceAtLeast(64_000)
            } else {
                plan.videoBitrateBps
            }

            val wants10BitHevc = config.codec == VideoCodecOption.H265 &&
                config.bitDepth == BitDepthOption.BIT_10 &&
                hwCaps.supportsHevc10Bit
            val wantsSdrToneMap = !wants10BitHevc

            val request = DirectTranscodeRequest(
                sourceUri = sourceUri,
                outputFile = outputFile,
                tempDir = outputFile.parentFile ?: context.cacheDir,
                videoMime = videoMime,
                preferredEncoderName = hwCaps.codecName,
                targetDisplayWidth = outW,
                targetDisplayHeight = outH,
                videoBitrateBps = effectiveVideoBps,
                targetFps = targetFps,
                useCbr = useCbr,
                dropTo30Fps = wants30Drop,
                hasAudio = media.hasAudio,
                forceAudioReencode = plan.forceAudioReencode,
                audioBitrateBps = plan.audioBitrateBps,
                clipStartUs = clipStartUs,
                clipEndUs = clipEndUs,
                effectiveDurationSec = plan.effectiveDurationSec,
                requestSdrToneMap = wantsSdrToneMap,
                wants10BitHevc = wants10BitHevc,
                supportsAbgr2101010 = hwCaps.supportsAbgr2101010,
                hdrEncoderName = hwCaps.hdrCodecName
            )

            DirectMediaCodecTranscoder.transcode(
                context = context,
                request = request,
                cancelFlag = cancelFlag,
                onProgress = onProgress
            )
        } finally {
            cancelHook?.dispose()
            activeCancelAction.set(null)
        }
    }

    private fun isRecoverableAttemptError(result: Media3TurboResult): Boolean {
        return result.errorCodeName in setOf(
            "ERROR_CODE_ENCODER_INIT_FAILED",
            "ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_ENCODING_FAILED",
            "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_AUDIO_PROCESSING_FAILED",
            "ERROR_CODE_MUXING_FAILED",
            "ERROR_CODE_MUXING_TIMEOUT",
            "ERROR_CODE_UNSPECIFIED"
        )
    }

    private suspend fun executeSingleAttempt(
        context: Context,
        media: MediaInfo,
        sourceUri: Uri,
        probe: SourceTrackProbe,
        hwCaps: HardwareVideoEncoderCaps,
        aacCaps: HardwareAudioEncoderCaps,
        plan: TurboBitratePlan,
        config: ModeConfigState,
        outputFile: File,
        attemptMode: EncoderAttemptMode,
        onProgress: (percent: Int, elapsedSec: Double, effectiveDurationSec: Double) -> Unit
    ): Media3TurboResult = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        val cancelledFlag = AtomicBoolean(false)
        val startUptimeMs = SystemClock.elapsedRealtime()
        val turboThread = HandlerThread("FFmpegMedia3Turbo", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
        }
        val turboLooper = turboThread.looper
        val turboHandler = Handler(turboLooper)

        fun completeOnce(result: Media3TurboResult) {
            if (finished.compareAndSet(false, true)) {
                activeCancelAction.set(null)
                runCatching { turboThread.quitSafely() }
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
                    val (startStr, endStr, _) = FfmpegMath.resolveTrimRange(config, probe.durationSec ?: media.durationSec)
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
                val videoEffects = mutableListOf<Effect>()
                val supportsScalingAndFps = isTargetSizeMode(config.mode) ||
                    (config.mode == ProcessingMode.TRIM_VIDEO &&
                        (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY || config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC))

                val srcFps = probe.sourceVideoFps ?: media.fps
                val needsFpsDrop = attemptMode == EncoderAttemptMode.SAFE_FPS_FALLBACK ||
                    (supportsScalingAndFps &&
                        config.fps == FpsOption.FPS_30 &&
                        (srcFps == null || srcFps > 30.5))
                if (needsFpsDrop) {
                    videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
                }

                val displayDims = probe.displayDims
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
                            val (outW, outH) = resolveTargetDimensionsForDisplay(dispW, dispH, config)
                            videoEffects.add(
                                Presentation.createForWidthAndHeight(
                                    outW,
                                    outH,
                                    Presentation.LAYOUT_SCALE_TO_FIT
                                )
                            )
                        } else if (srcShort > targetShort) {
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

                // Burn edited Hardsub cues via zero-garbage GPU OverlayEffect when in EMBED_SUBTITLES Hardsub mode
                if (config.mode == ProcessingMode.EMBED_SUBTITLES &&
                    config.subtitleEmbedMode == SubtitleEmbedMode.HARDSUB &&
                    config.subtitleCues.isNotEmpty()
                ) {
                    val subtitleOverlay = SubtitleBitmapOverlay(
                        context = context,
                        cues = config.subtitleCues,
                        fontOption = config.subtitleFont,
                        fontSizeOption = config.subtitleFontSize
                    )
                    videoEffects.add(OverlayEffect(listOf(subtitleOverlay)))
                }

                // Attach unity AudioProcessor ONLY when source is ALREADY AAC and we must re-encode it
                // to the user's exact target bitrate. Non-AAC tracks (Opus/MP3/FLAC/Vorbis) are already
                // re-encoded by setAudioMimeType(AUDIO_AAC) with zero extra buffer copy!
                val audioProcessors: List<AudioProcessor> = if (media.hasAudio && plan.sourceIsAac && plan.forceAudioReencode) {
                    listOf(ForceAudioEncodeProcessor())
                } else {
                    emptyList()
                }

                val effects = if (audioProcessors.isEmpty() && videoEffects.isEmpty()) {
                    Effects.EMPTY
                } else {
                    Effects(audioProcessors, videoEffects)
                }

                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(!media.hasAudio)
                    .setEffects(effects)
                    .build()

                // 3. Configure Composition with strict 10-bit preservation & resilient HDR->SDR handling:
                // - Whenever H.265 10-bit was chosen AND the device supports 10-bit HEVC, ALWAYS keep
                //   HDR_MODE_KEEP_HDR across every Media3 tier (never silently downgrade 10-bit to 8-bit!).
                // - When 8-bit was chosen: Tier 1 uses MediaCodec tone-mapping on Android 12+, fallback
                //   tiers use universal OpenGL ES 3.0 GPU tone-mapping.
                val wants10BitHevc = config.codec == VideoCodecOption.H265 &&
                    config.bitDepth == BitDepthOption.BIT_10 &&
                    hwCaps.supportsHevc10Bit
                val sourceIsHdr = probe.sourceIsHdr || media.isHdrVideo
                val hdrMode = when {
                    wants10BitHevc ->
                        Composition.HDR_MODE_KEEP_HDR
                    attemptMode != EncoderAttemptMode.OPTIMIZED_HW ->
                        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
                    else ->
                        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                }
                val composition = Composition.Builder(
                    EditedMediaItemSequence.Builder(editedMediaItem).build()
                )
                    .setHdrMode(hdrMode)
                    .build()

                // 4. Configure MediaCodec Hardware Video + Audio EncoderFactory per attempt tier
                val videoMime = when (config.codec) {
                    VideoCodecOption.H264 -> MimeTypes.VIDEO_H264
                    VideoCodecOption.H265 -> MimeTypes.VIDEO_H265
                }
                val (outW, outH) = if (displayDims != null) {
                    resolveTargetDimensionsForDisplay(displayDims.first, displayDims.second, config)
                } else {
                    resolveTargetDimensions(media, config)
                }
                val outPixels = outW.toLong() * outH.toLong()
                val effectiveFps = if (needsFpsDrop) 30.0 else (srcFps ?: 30.0).coerceIn(15.0, 120.0)

                val isHighResStream = outPixels > 1920L * 1080L
                val encoderFactory: Codec.EncoderFactory = when (attemptMode) {
                    EncoderAttemptMode.OPTIMIZED_HW -> {
                        if (wants10BitHevc) {
                            // 10-bit HEVC (4K or <=1080p, HDR or 10-bit SDR):
                            // Always use Media3's DefaultEncoderFactory with a finite operatingRate so
                            // COLOR_Format32bitABGR2101010, FEATURE_HdrEditing, KEY_COLOR_*, KEY_HDR_STATIC_INFO,
                            // and HEVCProfileMain10/Main10HDR10 are preserved cleanly (never stripped to 8-bit!).
                            val chipMaxFps = hwCaps.maxFpsForSize(outW, outH)
                            val safeOpRate = (chipMaxFps ?: effectiveFps).roundToInt().coerceIn(30, 120)
                            val settingsBuilder = VideoEncoderSettings.Builder()
                                .setBitrate(plan.videoBitrateBps)
                                .setiFrameIntervalSeconds(2f)
                                .setEncoderPerformanceParameters(safeOpRate, 0)
                            if (plan.useCbr && hwCaps.supportsCbr) {
                                settingsBuilder.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                            } else if (hwCaps.supportsVbr) {
                                settingsBuilder.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                            }
                            // For 10-bit SDR sources (or 8-bit sources targeting 10-bit HEVC), explicitly
                            // request HEVCProfileMain10 so DefaultEncoderFactory does not stay on 8-bit Main.
                            // For HDR sources, let DefaultEncoderFactory select Main10HDR10 (PQ) / Main10 (HLG)
                            // automatically alongside COLOR_Format32bitABGR2101010!
                            if (!sourceIsHdr && hwCaps.highestHevc10Level > 0) {
                                settingsBuilder.setEncodingProfileLevel(
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                    hwCaps.highestHevc10Level
                                )
                            }
                            val default10BitFactory = DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(settingsBuilder.build())
                                .setEnableFallback(false)
                                .build()
                            AudioBitrateWrapperEncoderFactory(
                                delegate = default10BitFactory,
                                targetAudioBitrateBps = plan.audioBitrateBps.takeIf { it > 0 },
                                aacCaps = aacCaps
                            )
                        } else if (isHighResStream && hwCaps.codecName != null) {
                            // On >1080p 8-bit (1440p / 4K / 4K60), strict Qualcomm C2 drivers reject
                            // Media3 DefaultEncoderFactory's operating-rate=INT_MAX & Level 5.1 locks.
                            // Use our minimal raw-style MediaFormat directly at full VPU clock speed
                            // (keeping CBR for strict target-size accuracy), while ensuring any portrait
                            // fallback delegate uses a finite operatingRate instead of Integer.MAX_VALUE.
                            val safeFallbackOpRate = (hwCaps.maxFpsForSize(outW, outH) ?: effectiveFps)
                                .roundToInt()
                                .coerceIn(30, 120)
                            val fallbackDelegateSettings = VideoEncoderSettings.Builder()
                                .setBitrate(plan.videoBitrateBps)
                                .setiFrameIntervalSeconds(2f)
                                .setEncoderPerformanceParameters(safeFallbackOpRate, 1)
                                .apply {
                                    if (plan.useCbr && hwCaps.supportsCbr) {
                                        setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                                    } else if (hwCaps.supportsVbr) {
                                        setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                                    }
                                    if (videoMime == MimeTypes.VIDEO_H264 &&
                                        outPixels >= 3840L * 2160L &&
                                        effectiveFps > 32.0 &&
                                        hwCaps.supportsAvcHighLevel52
                                    ) {
                                        setEncodingProfileLevel(
                                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                                            MediaCodecInfo.CodecProfileLevel.AVCLevel52
                                        )
                                    }
                                }
                                .build()
                            val delegateFactory = DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(fallbackDelegateSettings)
                                .setEnableFallback(true)
                                .build()

                            CleanHardwareEncoderFactory(
                                context = context,
                                delegate = delegateFactory,
                                preferredCodecName = hwCaps.codecName,
                                videoCapabilities = hwCaps.videoCapabilities,
                                aacCaps = aacCaps,
                                targetVideoMime = videoMime,
                                targetVideoBitrateBps = plan.videoBitrateBps,
                                targetAudioBitrateBps = plan.audioBitrateBps.takeIf { it > 0 },
                                useCbrIfSupported = plan.useCbr && hwCaps.supportsCbr,
                                maxSupportedFpsForSize = hwCaps.maxFpsForSize(outW, outH),
                                wants10BitHevc = false
                            )
                        } else {
                            val settingsBuilder = VideoEncoderSettings.Builder()
                                .setBitrate(plan.videoBitrateBps)
                                .setiFrameIntervalSeconds(2f)

                            if (plan.useCbr && hwCaps.supportsCbr) {
                                settingsBuilder.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                            } else if (hwCaps.supportsVbr) {
                                settingsBuilder.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                            }

                            // Use the hardware VPU's real maximum supported rate with realtime priority (0)
                            // so Qualcomm/OEM VPU drivers sustain full clock speed even when backgrounded.
                            val chipMaxFps = hwCaps.maxFpsForSize(outW, outH)
                            val safeOpRate = chipMaxFps?.roundToInt()?.coerceIn(30, 480) ?: 240
                            settingsBuilder.setEncoderPerformanceParameters(
                                /* operatingRate= */ safeOpRate,
                                /* priority= */ 0
                            )

                            val defaultFactory = DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(settingsBuilder.build())
                                .setEnableFallback(true)
                                .build()

                            AudioBitrateWrapperEncoderFactory(
                                delegate = defaultFactory,
                                targetAudioBitrateBps = plan.audioBitrateBps.takeIf { it > 0 },
                                aacCaps = aacCaps
                            )
                        }
                    }

                    EncoderAttemptMode.CLEAN_MINIMAL_HW,
                    EncoderAttemptMode.SAFE_FPS_FALLBACK -> {
                        val vbrFallbackFactor = if (plan.stressTier >= 2) 0.90 else 0.93
                        val fallbackAdjustedBps = if (plan.isTargetSizeMode && plan.useCbr) {
                            (plan.videoBitrateBps * vbrFallbackFactor).roundToInt().coerceAtLeast(64_000)
                        } else {
                            plan.videoBitrateBps
                        }
                        val cappedBitrate = if (attemptMode == EncoderAttemptMode.SAFE_FPS_FALLBACK) {
                            fallbackAdjustedBps.coerceAtMost(22_000_000)
                        } else {
                            fallbackAdjustedBps.coerceAtMost(24_000_000)
                        }
                        // Explicitly pass a finite operatingRate to DefaultEncoderFactory so Media3
                        // NEVER injects KEY_OPERATING_RATE = Integer.MAX_VALUE (2147483647) if
                        // portrait/alignment negotiation delegates back to DefaultEncoderFactory.
                        val delegateOpRate = if (attemptMode == EncoderAttemptMode.SAFE_FPS_FALLBACK) {
                            30
                        } else {
                            (hwCaps.maxFpsForSize(outW, outH) ?: effectiveFps).roundToInt().coerceIn(30, 120)
                        }
                        val fallbackVideoSettings = VideoEncoderSettings.Builder()
                            .setBitrate(cappedBitrate)
                            .setiFrameIntervalSeconds(2f)
                            .setEncoderPerformanceParameters(delegateOpRate, 1)
                            .apply {
                                if (wants10BitHevc && !sourceIsHdr && hwCaps.highestHevc10Level > 0) {
                                    setEncodingProfileLevel(
                                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                        hwCaps.highestHevc10Level
                                    )
                                }
                                if (videoMime == MimeTypes.VIDEO_H264 &&
                                    attemptMode != EncoderAttemptMode.SAFE_FPS_FALLBACK &&
                                    outPixels >= 3840L * 2160L &&
                                    effectiveFps > 32.0 &&
                                    hwCaps.supportsAvcHighLevel52
                                ) {
                                    setEncodingProfileLevel(
                                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                                        MediaCodecInfo.CodecProfileLevel.AVCLevel52
                                    )
                                }
                            }
                            .build()
                        val audioAndFallbackDelegate = DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(fallbackVideoSettings)
                            .setEnableFallback(!wants10BitHevc)
                            .build()

                        if (wants10BitHevc) {
                            AudioBitrateWrapperEncoderFactory(
                                delegate = audioAndFallbackDelegate,
                                targetAudioBitrateBps = plan.audioBitrateBps.takeIf { it > 0 },
                                aacCaps = aacCaps
                            )
                        } else {
                            CleanHardwareEncoderFactory(
                                context = context,
                                delegate = audioAndFallbackDelegate,
                                preferredCodecName = hwCaps.codecName,
                                videoCapabilities = hwCaps.videoCapabilities,
                                aacCaps = aacCaps,
                                targetVideoMime = videoMime,
                                targetVideoBitrateBps = cappedBitrate,
                                targetAudioBitrateBps = plan.audioBitrateBps.takeIf { it > 0 },
                                useCbrIfSupported = false,
                                maxSupportedFpsForSize = if (attemptMode == EncoderAttemptMode.SAFE_FPS_FALLBACK) {
                                    30.0
                                } else {
                                    hwCaps.maxFpsForSize(outW, outH)
                                },
                                wants10BitHevc = false
                            )
                        }
                    }
                }

                val progressHolder = ProgressHolder()
                var progressPoller: Runnable? = null

                fun stopPolling() {
                    progressPoller?.let { turboHandler.removeCallbacks(it) }
                    progressPoller = null
                }

                val transformerBuilder = Transformer.Builder(context)
                    .setLooper(turboLooper)
                    .setVideoMimeType(videoMime)
                    .setEncoderFactory(encoderFactory)
                    .setMaxDelayBetweenMuxerSamplesMs(30_000L)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            stopPolling()
                            val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            val hasOutAudio = !media.hasAudio ||
                                (!exportResult.audioMimeType.isNullOrBlank() && exportResult.channelCount > 0)
                            val out10Bit = exportResult.colorInfo?.lumaBitdepth == 10
                            onProgress(100, elapsedSec, plan.effectiveDurationSec)
                            completeOnce(
                                Media3TurboResult(
                                    success = true,
                                    elapsedSec = elapsedSec,
                                    outputHasAudio = hasOutAudio,
                                    outputIs10Bit = if (out10Bit) true else null
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

                // Keep AAC stream pass-through only when audio copy is requested OR target-size AAC is already compact
                val wantsAudioCopy = config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
                    config.mode == ProcessingMode.EMBED_SUBTITLES ||
                    (config.mode == ProcessingMode.TRIM_VIDEO && config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY)
                if (media.hasAudio && (plan.forceAudioReencode || !(wantsAudioCopy && plan.sourceIsAac))) {
                    transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                }

                val transformer = transformerBuilder.build()

                val cancelOnTurboLooper: () -> Unit = {
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
                val cancelBlock: () -> Unit = {
                    if (Looper.myLooper() == turboLooper) {
                        cancelOnTurboLooper()
                    } else if (!turboHandler.post { cancelOnTurboLooper() }) {
                        cancelledFlag.set(true)
                        completeOnce(
                            Media3TurboResult(
                                success = false,
                                cancelled = true,
                                elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                            )
                        )
                    }
                }
                activeCancelAction.set(cancelBlock)

                // Poll progress calmly (~4 Hz -> smoothly interpolated at 120Hz in Draw phase without CPU churn)
                progressPoller = object : Runnable {
                    override fun run() {
                        if (finished.get() || cancelledFlag.get()) return
                        val elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                        val state = runCatching { transformer.getProgress(progressHolder) }
                            .getOrDefault(Transformer.PROGRESS_STATE_UNAVAILABLE)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 99), elapsedSec, plan.effectiveDurationSec)
                        } else {
                            onProgress(-1, elapsedSec, plan.effectiveDurationSec)
                        }
                        if (!finished.get() && !cancelledFlag.get()) {
                            turboHandler.postDelayed(this, 250L)
                        }
                    }
                }

                transformer.start(composition, outputFile.absolutePath)
                turboHandler.postDelayed(progressPoller!!, 120L)
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

        // Allow early cancellation even before startRunnable initializes Transformer
        activeCancelAction.set {
            cancelledFlag.set(true)
            completeOnce(
                Media3TurboResult(
                    success = false,
                    cancelled = true,
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            )
        }

        cont.invokeOnCancellation {
            cancelActive()
        }

        if (!turboHandler.post(startRunnable)) {
            completeOnce(
                Media3TurboResult(
                    success = false,
                    cancelled = cancelledFlag.get(),
                    errorCodeName = "TURBO_LOOPER_START_FAILED",
                    errorDetail = "Failed to schedule Media3 Transformer on worker Looper",
                    elapsedSec = (SystemClock.elapsedRealtime() - startUptimeMs) / 1000.0
                )
            )
        }
    }

    /**
     * Probes real post-rotation display dimensions, exact duration, source total bitrate,
     * HDR transfer/color metadata, and source audio track MIME/bitrate/channels/sample-rate.
     */
    private fun probeSourceTracks(
        context: Context,
        uri: Uri,
        fallbackMedia: MediaInfo
    ): SourceTrackProbe {
        var displayDims: Pair<Int, Int>? = if (fallbackMedia.width != null && fallbackMedia.height != null) {
            fallbackMedia.width to fallbackMedia.height
        } else null
        var durationSec: Double? = fallbackMedia.durationSec
        var totalBitrateBps: Int? = null
        var audioBitrateBps: Int? = fallbackMedia.audioBitrateKbps?.times(1000)
        var audioMime: String? = null
        var audioChannels: Int? = fallbackMedia.audioChannels
        var audioSampleRate: Int? = null
        var videoFps: Double? = fallbackMedia.fps
        var sourceIsHdr: Boolean = fallbackMedia.isHdrVideo

        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                retriever.setDataSource(uri.path)
            } else {
                val openedDirect = runCatching {
                    retriever.setDataSource(context, uri)
                    true
                }.getOrDefault(false)
                if (!openedDirect) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    }
                }
            }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w != null && h != null && w > 0 && h > 0) {
                displayDims = if (rot == 90 || rot == 270) (h to w) else (w to h)
            }
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durMs != null && durMs > 0L) {
                durationSec = durMs / 1000.0
            }
            val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            if (br != null && br > 0) {
                totalBitrateBps = br
            }
        } catch (_: Throwable) {
            // Fall back to MediaInfo fields
        } finally {
            runCatching { retriever.release() }
        }

        if (totalBitrateBps == null && fallbackMedia.sizeBytes > 0L && durationSec != null && durationSec > 0.5) {
            totalBitrateBps = ((fallbackMedia.sizeBytes * 8.0) / durationSec).roundToInt()
        }

        val extractor = MediaExtractor()
        try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                val openedDirect = runCatching {
                    extractor.setDataSource(context, uri, null)
                    true
                }.getOrDefault(false)
                if (!openedDirect) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                }
            }
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                    if (trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        val abr = runCatching { trackFormat.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                        if (abr != null && abr > 0) audioBitrateBps = abr
                    }
                    if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val ch = runCatching { trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()
                        if (ch != null && ch > 0) audioChannels = ch
                    }
                    if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val sr = runCatching { trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
                        if (sr != null && sr > 0) audioSampleRate = sr
                    }
                } else if (mime.startsWith("video/")) {
                    if (DirectTranscodeMath.isMediaFormatHdr(trackFormat)) {
                        sourceIsHdr = true
                    }
                    if (trackFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val fps = runCatching { trackFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                            .getOrElse { runCatching { trackFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
                        if (fps != null && fps in 1.0..240.0) {
                            val currentFps = videoFps
                            if (currentFps == null || fps > currentFps + 1.0) {
                                videoFps = fps
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Optional refinement only
        } finally {
            runCatching { extractor.release() }
        }

        return SourceTrackProbe(
            displayDims = displayDims,
            durationSec = durationSec,
            sourceTotalBitrateBps = totalBitrateBps,
            sourceAudioBitrateBps = audioBitrateBps,
            sourceAudioMime = audioMime,
            sourceAudioChannels = audioChannels,
            sourceAudioSampleRate = audioSampleRate,
            sourceVideoFps = videoFps,
            sourceIsHdr = sourceIsHdr
        )
    }

    /**
     * Queries and caches the device's real hardware video encoder capabilities for [mimeType],
     * including Android 13+ `FEATURE_HdrEditing`, `COLOR_Format32bitABGR2101010`, and highest
     * `HEVCProfileMain10` encoding level.
     */
    private fun queryHardwareVideoEncoderCaps(mimeType: String): HardwareVideoEncoderCaps {
        videoCapsCache[mimeType]?.let { return it }
        val resolved = runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val hwEncoders = codecList.codecInfos.filter { info ->
                if (!info.isEncoder) return@filter false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) return@filter false
                if (info.name.startsWith("OMX.google.", ignoreCase = true) ||
                    info.name.startsWith("c2.android.", ignoreCase = true)
                ) return@filter false
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            val fallbackEncoder = if (hwEncoders.isEmpty()) {
                codecList.codecInfos.firstOrNull { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                }
            } else null
            val primaryEncoder = hwEncoders.firstOrNull() ?: fallbackEncoder

            if (primaryEncoder == null) {
                return@runCatching HardwareVideoEncoderCaps(null, 100_000, 32_000_000, false, false, false, false, null)
            }

            // On Snapdragon/Android 13+, devices may expose a dedicated HDR-capable HEVC encoder entry.
            var hdrCodecName: String? = null
            var supportsHdrEdit = false
            var supportsAbgr10 = false
            var highestHevc10Level = -1
            var anyHevc10 = false

            if (mimeType == MimeTypes.VIDEO_H265) {
                for (enc in (hwEncoders.ifEmpty { listOf(primaryEncoder) })) {
                    val encCaps = runCatching { enc.getCapabilitiesForType(mimeType) }.getOrNull() ?: continue
                    val main10Levels = encCaps.profileLevels.orEmpty().filter {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                    }
                    if (main10Levels.isNotEmpty()) {
                        anyHevc10 = true
                        val maxLvl = encCaps.profileLevels.orEmpty()
                            .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                            .maxOfOrNull { it.level } ?: -1
                        if (maxLvl > highestHevc10Level) {
                            highestHevc10Level = maxLvl
                        }
                        val hasAbgr = encCaps.colorFormats?.contains(0x7F00AAA2) == true
                        val hasHdrFeat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            encCaps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
                        if (hasAbgr) supportsAbgr10 = true
                        if (hasHdrFeat && hasAbgr) {
                            supportsHdrEdit = true
                            if (hdrCodecName == null) hdrCodecName = enc.name
                        } else if (hdrCodecName == null) {
                            hdrCodecName = enc.name
                        }
                    }
                }
            }

            val caps = primaryEncoder.getCapabilitiesForType(mimeType)
            val videoCaps = caps?.videoCapabilities
            val encoderCaps = caps?.encoderCapabilities
            val minBr = videoCaps?.bitrateRange?.lower ?: 100_000
            val maxBr = videoCaps?.bitrateRange?.upper ?: 32_000_000
            val supportsCbr = encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
            val supportsVbr = encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
            val supports52 = mimeType == MimeTypes.VIDEO_H264 &&
                (caps?.profileLevels?.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh &&
                        it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel52
                } == true)

            HardwareVideoEncoderCaps(
                codecName = primaryEncoder.name,
                minBitrateBps = minBr.coerceAtLeast(64_000),
                maxBitrateBps = maxBr.coerceAtLeast(2_000_000),
                supportsCbr = supportsCbr,
                supportsVbr = supportsVbr,
                supportsAvcHighLevel52 = supports52,
                supportsHevc10Bit = anyHevc10,
                videoCapabilities = videoCaps,
                hdrCodecName = hdrCodecName ?: primaryEncoder.name,
                supportsHdrEditing = supportsHdrEdit,
                supportsAbgr2101010 = supportsAbgr10,
                highestHevc10Level = highestHevc10Level
            )
        }.getOrElse {
            HardwareVideoEncoderCaps(null, 100_000, 32_000_000, false, false, false, false, null)
        }
        videoCapsCache[mimeType] = resolved
        return resolved
    }

    private fun defaultAacCaps() = HardwareAudioEncoderCaps(
        minBitrateBps = 32_000,
        maxBitrateBps = 510_000,
        maxChannelCount = 6,
        minSampleRateHz = 8_000,
        maxSampleRateHz = 48_000
    )

    private fun queryAacEncoderCaps(): HardwareAudioEncoderCaps {
        cachedAacCaps?.let { return it }
        val resolved = runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val aacEncoder = codecList.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.AUDIO_AAC, ignoreCase = true) }
            } ?: return@runCatching defaultAacCaps()

            val audioCaps = aacEncoder.getCapabilitiesForType(MimeTypes.AUDIO_AAC)?.audioCapabilities
                ?: return@runCatching defaultAacCaps()
            val minBr = audioCaps.bitrateRange.lower.coerceAtLeast(16_000)
            val maxBr = audioCaps.bitrateRange.upper.coerceAtLeast(128_000)
            val maxCh = audioCaps.maxInputChannelCount.coerceIn(1, 8)
            val srRanges = audioCaps.supportedSampleRateRanges
            val minSr = srRanges?.minOfOrNull { it.lower }?.coerceAtLeast(8_000) ?: 8_000
            val maxSr = srRanges?.maxOfOrNull { it.upper }?.coerceAtLeast(44_100) ?: 48_000

            HardwareAudioEncoderCaps(
                minBitrateBps = minBr,
                maxBitrateBps = maxBr,
                maxChannelCount = maxCh,
                minSampleRateHz = minSr,
                maxSampleRateHz = maxSr
            )
        }.getOrElse { defaultAacCaps() }
        cachedAacCaps = resolved
        return resolved
    }

    private fun isUnsupportedFormatException(ex: ExportException): Boolean {
        return when (ex.errorCode) {
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
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

    private fun alignDimension(value: Int, alignment: Int, minVal: Int, maxVal: Int): Int {
        val safeAlign = alignment.coerceAtLeast(2)
        val clamped = value.coerceIn(minVal.coerceAtLeast(safeAlign), maxVal.coerceAtLeast(safeAlign))
        val aligned = (clamped / safeAlign) * safeAlign
        return aligned.coerceAtLeast(safeAlign)
    }

    private fun applySafeAudioFormat(
        format: Format,
        targetAudioBitrateBps: Int?,
        aacCaps: HardwareAudioEncoderCaps
    ): Format {
        val builder = format.buildUpon()
        val channels = if (format.channelCount > 0) format.channelCount else 2
        if (targetAudioBitrateBps != null && targetAudioBitrateBps > 0) {
            val safeBr = aacCaps.clampBitrateForChannels(targetAudioBitrateBps, channels)
            builder.setAverageBitrate(safeBr).setPeakBitrate(safeBr)
        }
        if (format.sampleRate > 0) {
            val safeSr = aacCaps.clampSampleRate(format.sampleRate)
            if (safeSr != format.sampleRate) {
                builder.setSampleRate(safeSr)
            }
        }
        return builder.build()
    }

    /**
     * Delegates video encoding to Media3's DefaultEncoderFactory while injecting the exact
     * user-selected audio bitrate (clamped only to device AAC encoder hardware/platform ceilings).
     */
    private class AudioBitrateWrapperEncoderFactory(
        private val delegate: Codec.EncoderFactory,
        private val targetAudioBitrateBps: Int?,
        private val aacCaps: HardwareAudioEncoderCaps
    ) : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            val adjusted = applySafeAudioFormat(format, targetAudioBitrateBps, aacCaps)
            return delegate.createForAudioEncoding(adjusted, logSessionId)
        }

        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            return delegate.createForVideoEncoding(format, logSessionId)
        }

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
    }

    /**
     * Minimal, OEM-safe hardware MediaCodec encoder factory for 8-bit >1080p streams that omits
     * `operating-rate`, `priority`, `profile`, and `level` overrides so strict Qualcomm/Exynos/MTK
     * C2 drivers accept lossless-cut and 4K60 streams. Always delegates 10-bit/HDR streams directly
     * to [delegate] so 10-bit color formats and Main10 profiles are never stripped.
     */
    private class CleanHardwareEncoderFactory(
        private val context: Context,
        private val delegate: Codec.EncoderFactory,
        private val preferredCodecName: String?,
        private val videoCapabilities: MediaCodecInfo.VideoCapabilities?,
        private val aacCaps: HardwareAudioEncoderCaps,
        private val targetVideoMime: String,
        private val targetVideoBitrateBps: Int,
        private val targetAudioBitrateBps: Int?,
        private val useCbrIfSupported: Boolean,
        private val maxSupportedFpsForSize: Double?,
        private val wants10BitHevc: Boolean = false
    ) : Codec.EncoderFactory {

        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            val adjusted = applySafeAudioFormat(format, targetAudioBitrateBps, aacCaps)
            return delegate.createForAudioEncoding(adjusted, logSessionId)
        }

        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            // Never strip 10-bit or HDR metadata down to bare 8-bit COLOR_FormatSurface
            if (wants10BitHevc || ColorInfo.isTransferHdr(format.colorInfo)) {
                return delegate.createForVideoEncoding(format, logSessionId)
            }
            val mime = format.sampleMimeType ?: targetVideoMime
            val codecName = preferredCodecName ?: return delegate.createForVideoEncoding(format, logSessionId)
            val vc = videoCapabilities
            val width = if (vc != null) {
                alignDimension(
                    value = format.width.coerceAtLeast(16),
                    alignment = vc.widthAlignment,
                    minVal = vc.supportedWidths.lower,
                    maxVal = vc.supportedWidths.upper
                )
            } else {
                makeEven(format.width.coerceAtLeast(16))
            }
            val height = if (vc != null) {
                alignDimension(
                    value = format.height.coerceAtLeast(16),
                    alignment = vc.heightAlignment,
                    minVal = vc.supportedHeights.lower,
                    maxVal = vc.supportedHeights.upper
                )
            } else {
                makeEven(format.height.coerceAtLeast(16))
            }

            val rawFps = if (format.frameRate > 1f) format.frameRate.toDouble() else 30.0
            val safeFps = if (maxSupportedFpsForSize != null && maxSupportedFpsForSize >= 15.0) {
                minOf(rawFps, maxSupportedFpsForSize).roundToInt().coerceIn(15, 120)
            } else {
                rawFps.roundToInt().coerceIn(15, 60)
            }

            val configFormat = format.buildUpon()
                .setSampleMimeType(mime)
                .setWidth(width)
                .setHeight(height)
                .setFrameRate(safeFps.toFloat())
                .setAverageBitrate(targetVideoBitrateBps)
                .build()

            // If this hardware encoder rejects portrait dimensions or this size directly, let Media3's
            // DefaultEncoderFactory handle rotation/size fallback cleanly without crashing.
            val sizeSupported = vc == null || runCatching { vc.isSizeSupported(width, height) }.getOrDefault(false)
            if (!sizeSupported) {
                return delegate.createForVideoEncoding(configFormat, logSessionId)
            }

            val mediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetVideoBitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (useCbrIfSupported) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                }
            }

            return runCatching {
                DefaultCodec(
                    context,
                    configFormat,
                    mediaFormat,
                    codecName,
                    /* isDecoder= */ false,
                    /* outputSurface= */ null
                )
            }.getOrElse {
                delegate.createForVideoEncoding(configFormat, logSessionId)
            }
        }

        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

        override fun videoNeedsEncoding(): Boolean = true
    }

    /**
     * Zero-cost unity AudioProcessor that ensures Media3 re-encodes AAC audio at our exact
     * target bitrate instead of silently transmuxing a higher-bitrate source AAC track.
     */
    private class ForceAudioEncodeProcessor : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining <= 0) return
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        }
    }
}
