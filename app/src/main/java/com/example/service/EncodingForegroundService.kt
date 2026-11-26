package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.data.DeviceCodecInventory
import com.example.data.FfmpegEngine
import com.example.data.Media3TurboEngine
import com.example.data.MediaStoreSaver
import com.example.data.PreferencesRepository
import com.example.data.FfmpegStepResult
import com.example.data.TurboBitratePlan
import com.example.domain.AppLanguageController
import com.example.domain.AppStrings
import com.example.domain.CompletedOutput
import com.example.domain.CpuSimdProfile
import com.example.domain.CustomCommandTool
import com.example.domain.CustomFfmpegParser
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.Media3CompatibilityAdvisor
import com.example.domain.Media3CompatibilityReport
import com.example.domain.Media3IssueKind
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.TrimCutStrategy
import com.example.domain.TrimSubMode
import com.example.domain.TrimTimeChoice
import com.example.domain.TurboRecoveryStep
import com.example.domain.VideoCodecOption
import com.example.domain.localizedLabel
import com.example.domain.localizedTitle
import com.example.domain.supportsMedia3HardwareTurbo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PendingEncodingJob(
    val runId: Long,
    val media: MediaInfo,
    val config: ModeConfigState,
    val audioConvertBitrateKbps: Int? = null,
    val turboTargetConfig: ModeConfigState? = null
)

/**
 * Process-scoped state holder shared between EncodingForegroundService and MainViewModel so
 * progress survives configuration changes, backgrounding, and screen lock during long encodes.
 */
object EncodingJobController {
    private val _state = MutableStateFlow<EncodingState>(EncodingState.Idle)
    val state: StateFlow<EncodingState> = _state.asStateFlow()

    private val _cancelGeneration = MutableStateFlow(0L)
    val cancelGeneration: StateFlow<Long> = _cancelGeneration.asStateFlow()

    private val runCounter = AtomicLong(0L)
    private val hasActiveTurboEngine = AtomicBoolean(false)

    @Volatile
    private var activeRunId: Long = 0L

    @Volatile
    private var cancelledRunId: Long = -1L

    @Volatile
    var pendingJob: PendingEncodingJob? = null

    @Volatile
    var activeJobSnapshot: PendingEncodingJob? = null

    fun beginNewRun(): Long {
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        return nextId
    }

    /**
     * Atomically cancels any prior run and transitions immediately into a standard FFmpeg Running
     * state without waiting for Android's startForegroundService dispatch, eliminating tap-to-slide
     * latency on both Config -> Processing and Turbo Failed -> FFmpeg Fallback.
     */
    fun startStandardRun(
        media: MediaInfo,
        config: ModeConfigState,
        initialRunningState: EncodingState.Running
    ): Long {
        val prev = activeRunId
        if (prev > 0L) {
            cancelledRunId = prev
        }
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        val nextJob = PendingEncodingJob(nextId, media, config)
        pendingJob = nextJob
        activeJobSnapshot = nextJob
        hasActiveTurboEngine.set(false)
        _state.value = initialRunningState
        return nextId
    }

    fun currentActiveRunId(): Long = activeRunId

    fun isRunActive(runId: Long): Boolean {
        return runId > 0L && activeRunId == runId && cancelledRunId != runId
    }

    fun updateStateIfActive(runId: Long, newState: EncodingState) {
        if (isRunActive(runId)) {
            _state.value = newState
        }
    }

    fun markTurboEngineActive(active: Boolean) {
        hasActiveTurboEngine.set(active)
    }

    fun clearTurboEngineIfOwner(runId: Long) {
        if (activeRunId == runId || activeRunId == 0L) {
            hasActiveTurboEngine.set(false)
        }
    }

    /**
     * Atomically cancels an active FFmpeg job and hands off immediately to Media3 Hardware Turbo
     * without ever emitting EncodingState.Idle, enabling a seamless vertical slide transition.
     */
    fun switchActiveRunToTurbo(
        media: MediaInfo,
        config: ModeConfigState,
        initialTurboState: EncodingState.Running
    ): Long {
        val prev = activeRunId
        if (prev > 0L) {
            cancelledRunId = prev
        }
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        val nextJob = PendingEncodingJob(nextId, media, config)
        pendingJob = nextJob
        activeJobSnapshot = nextJob
        hasActiveTurboEngine.set(true)
        _state.value = initialTurboState
        runCatching { FFmpegKit.cancel() }
        return nextId
    }

    /**
     * Atomically hands off from a failed Turbo run to a one-time lossless FFmpeg MP4 remux
     * without ever emitting Idle, enabling Slide 1 (Turbo -> FFmpeg Processing).
     */
    fun switchActiveRunToRemuxThenTurbo(
        media: MediaInfo,
        config: ModeConfigState,
        initialRemuxState: EncodingState.Running
    ): Long {
        val prev = activeRunId
        if (prev > 0L) {
            cancelledRunId = prev
        }
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        val nextJob = PendingEncodingJob(nextId, media, config)
        pendingJob = nextJob
        activeJobSnapshot = nextJob
        hasActiveTurboEngine.set(false)
        _state.value = initialRemuxState
        return nextId
    }

    /**
     * Atomically hands off from a failed Turbo run to an Audio-Only AAC conversion (-c:v copy)
     * without ever emitting Idle, then automatically slides back to the Turbo speedometer.
     */
    fun switchActiveRunToAudioConvertThenTurbo(
        media: MediaInfo,
        config: ModeConfigState,
        audioBitrateKbps: Int,
        initialAudioState: EncodingState.Running
    ): Long {
        val prev = activeRunId
        if (prev > 0L) {
            cancelledRunId = prev
        }
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        val nextJob = PendingEncodingJob(
            runId = nextId,
            media = media,
            config = config,
            audioConvertBitrateKbps = audioBitrateKbps
        )
        pendingJob = nextJob
        activeJobSnapshot = nextJob
        hasActiveTurboEngine.set(false)
        _state.value = initialAudioState
        return nextId
    }

    /**
     * Atomically launches a user-configured Video+Audio FFmpeg preparation encode that will
     * automatically slide back to the Turbo speedometer to run [turboTargetConfig] once ready.
     */
    fun startVideoAudioPrepThenTurbo(
        media: MediaInfo,
        prepConfig: ModeConfigState,
        turboTargetConfig: ModeConfigState,
        initialPrepState: EncodingState.Running
    ): Long {
        val prev = activeRunId
        if (prev > 0L) {
            cancelledRunId = prev
        }
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        pendingJob = PendingEncodingJob(
            runId = nextId,
            media = media,
            config = prepConfig,
            turboTargetConfig = turboTargetConfig
        )
        // Preserve the user's real Turbo target config in activeJobSnapshot so any subsequent
        // FFmpeg fallback runs the original target (e.g. Discord 20 MB / Trim / CRF).
        activeJobSnapshot = PendingEncodingJob(nextId, media, turboTargetConfig)
        hasActiveTurboEngine.set(false)
        _state.value = initialPrepState
        return nextId
    }

    /**
     * Atomically invalidates the active run so no late FFmpegKit or Media3 progress/log/error
     * callback can ever reopen ProcessingScreen or trap the user, cancels native engines,
     * and returns to Idle. Returns the cancelled runId (or 0L if already Idle).
     */
    fun cancelActiveRun(): Long {
        val current = activeRunId
        val wasIdle = current == 0L && _state.value is EncodingState.Idle
        if (wasIdle) return 0L
        if (current > 0L) {
            cancelledRunId = current
        }
        activeRunId = 0L
        pendingJob = null
        activeJobSnapshot = null
        _state.value = EncodingState.Idle
        _cancelGeneration.value = _cancelGeneration.value + 1L
        runCatching { FFmpegKit.cancel() }
        if (hasActiveTurboEngine.getAndSet(false)) {
            runCatching { Media3TurboEngine.cancelActive() }
        }
        return current
    }

    fun forceResetToIdle() {
        activeRunId = 0L
        pendingJob = null
        activeJobSnapshot = null
        hasActiveTurboEngine.set(false)
        _state.value = EncodingState.Idle
    }
}

class EncodingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "fflab_live_encoding_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.fflab.ACTION_START_ENCODING"
        const val ACTION_START_TURBO = "com.example.fflab.ACTION_START_TURBO_ENCODING"
        const val ACTION_REMUX_THEN_TURBO = "com.example.fflab.ACTION_REMUX_THEN_TURBO"
        const val ACTION_AUDIO_CONVERT_THEN_TURBO = "com.example.fflab.ACTION_AUDIO_CONVERT_THEN_TURBO"
        const val ACTION_VIDEO_AUDIO_PREP_THEN_TURBO = "com.example.fflab.ACTION_VIDEO_AUDIO_PREP_THEN_TURBO"
        const val ACTION_CANCEL = "com.example.fflab.ACTION_CANCEL_ENCODING"
        private const val EXTRA_CANCELLED_RUN_ID = "extra_cancelled_run_id"

        internal fun formatDiagnosisSummary(report: Media3CompatibilityReport, s: AppStrings): String? = when (report.issueKind) {
            Media3IssueKind.COMPATIBLE -> null
            Media3IssueKind.CONTAINER_ONLY ->
                s.turboDiagContainerOnly(report.containerIssueTag ?: report.containerName)
            Media3IssueKind.AUDIO_INCOMPATIBLE ->
                s.turboDiagAudioIncompatible(report.audioIssueTag ?: report.audioCodecName ?: "AUDIO")
            Media3IssueKind.VIDEO_INCOMPATIBLE -> {
                val vTag = report.videoIssueTag ?: report.videoCodecName ?: "VIDEO"
                if (report.canUseVideoOnlyPrep) {
                    s.turboDiagVideoOnlyIncompatible(vTag, report.audioCodecName)
                } else {
                    s.turboDiagVideoIncompatible(vTag)
                }
            }
            Media3IssueKind.VIDEO_AND_AUDIO_INCOMPATIBLE ->
                s.turboDiagVideoAndAudioIncompatible(
                    report.videoIssueTag ?: report.videoCodecName ?: "VIDEO",
                    report.audioIssueTag ?: report.audioCodecName ?: "AUDIO"
                )
        }

        fun startEncoding(context: Context, media: MediaInfo, config: ModeConfigState) {
            val s = AppLanguageController.currentStrings()
            val modeTitle = "${config.mode.formattedNumber} · ${config.mode.localizedTitle(s)}"
            val expectedOutputName = FfmpegMath.computeOutputFileName(media, config, s)
            val summaryBanner = buildSummaryBanner(media, config, s)
            val presetLabel = resolvePresetDisplayLabel(config)
            val canSwitchToTurbo = supportsMedia3HardwareTurbo(media, config)
            val initialPasses = when (config.mode) {
                ProcessingMode.DISCORD_20MB,
                ProcessingMode.WHATSAPP_100MB,
                ProcessingMode.GMAIL_25MB,
                ProcessingMode.CUSTOM_SIZE -> 2
                else -> 1
            }
            val expectedDur = if (config.mode == ProcessingMode.TRIM_VIDEO) {
                FfmpegMath.resolveTrimRange(config, media.durationSec).third ?: media.durationSec
            } else {
                media.durationSec
            }
            val initialRunning = EncodingState.Running(
                modeTitle = modeTitle,
                outputFileName = expectedOutputName,
                passIndex = 1,
                totalPasses = initialPasses,
                statusMessage = s.notifPreparing,
                currentTimeSec = 0.0,
                totalDurationSec = expectedDur,
                percent = if (expectedDur != null) 0 else null,
                speed = "...",
                sizeKb = 0L,
                logs = emptyList(),
                summaryBanner = summaryBanner,
                videoSpecsSummary = null,
                presetLabel = presetLabel,
                isHardwareTurbo = false,
                canSwitchToTurbo = canSwitchToTurbo
            )
            EncodingJobController.startStandardRun(media, config, initialRunning)
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startTurboEncoding(context: Context, media: MediaInfo, config: ModeConfigState) {
            val s = AppLanguageController.currentStrings()
            val modeTitle = "${config.mode.formattedNumber} · ${config.mode.localizedTitle(s)}"
            val baseOut = FfmpegMath.computeOutputFileName(media, config, s)
            val turboOut = Media3TurboEngine.buildTurboOutputFileName(baseOut)
            val unifiedSpecs = buildTurboUnifiedSpecs(media, config, s)
            val expectedDur = if (config.mode == ProcessingMode.TRIM_VIDEO) {
                FfmpegMath.resolveTrimRange(config, media.durationSec).third ?: media.durationSec
            } else {
                media.durationSec
            }
            val initialRunning = EncodingState.Running(
                modeTitle = modeTitle,
                outputFileName = turboOut,
                passIndex = 1,
                totalPasses = 1,
                statusMessage = s.turboStatusPreparing,
                currentTimeSec = 0.0,
                totalDurationSec = expectedDur,
                percent = 0,
                speed = "...",
                sizeKb = 0L,
                logs = emptyList(),
                summaryBanner = unifiedSpecs,
                videoSpecsSummary = unifiedSpecs,
                presetLabel = "MediaCodec",
                isHardwareTurbo = true,
                canSwitchToTurbo = false,
                elapsedSec = 0.0
            )
            EncodingJobController.switchActiveRunToTurbo(media, config, initialRunning)
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_START_TURBO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startRemuxThenTurbo(context: Context, media: MediaInfo, config: ModeConfigState) {
            val s = AppLanguageController.currentStrings()
            val expectedDur = if (config.mode == ProcessingMode.TRIM_VIDEO) {
                FfmpegMath.resolveTrimRange(config, media.durationSec).third ?: media.durationSec
            } else {
                media.durationSec
            }
            val initialRemux = EncodingState.Running(
                modeTitle = s.turboRemuxModeTitle,
                outputFileName = "turbo_remux.mp4",
                passIndex = 1,
                totalPasses = 1,
                statusMessage = s.turboRemuxStepStatus,
                currentTimeSec = 0.0,
                totalDurationSec = expectedDur,
                percent = if (expectedDur != null) 0 else null,
                speed = "...",
                sizeKb = 0L,
                logs = emptyList(),
                summaryBanner = s.bannerRemux,
                videoSpecsSummary = null,
                presetLabel = null,
                isHardwareTurbo = false,
                canSwitchToTurbo = false,
                isTurboPreRemux = true
            )
            EncodingJobController.switchActiveRunToRemuxThenTurbo(media, config, initialRemux)
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_REMUX_THEN_TURBO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startAudioConvertThenTurbo(
            context: Context,
            media: MediaInfo,
            config: ModeConfigState,
            audioBitrateKbps: Int
        ) {
            val s = AppLanguageController.currentStrings()
            val safeAb = audioBitrateKbps.coerceIn(32, 768)
            val expectedDur = if (config.mode == ProcessingMode.TRIM_VIDEO) {
                FfmpegMath.resolveTrimRange(config, media.durationSec).third ?: media.durationSec
            } else {
                media.durationSec
            }
            val initialAudio = EncodingState.Running(
                modeTitle = s.turboAudioConvertModeTitle,
                outputFileName = "turbo_audio_aac.mp4",
                passIndex = 1,
                totalPasses = 1,
                statusMessage = s.turboAudioConvertStepStatus(safeAb),
                currentTimeSec = 0.0,
                totalDurationSec = expectedDur,
                percent = if (expectedDur != null) 0 else null,
                speed = "...",
                sizeKb = 0L,
                logs = emptyList(),
                summaryBanner = s.bannerAudioOnly(safeAb),
                videoSpecsSummary = null,
                presetLabel = null,
                isHardwareTurbo = false,
                canSwitchToTurbo = false,
                isTurboPreRemux = true
            )
            EncodingJobController.switchActiveRunToAudioConvertThenTurbo(media, config, safeAb, initialAudio)
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_AUDIO_CONVERT_THEN_TURBO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startVideoAudioPrepThenTurbo(
            context: Context,
            media: MediaInfo,
            prepConfig: ModeConfigState,
            turboTargetConfig: ModeConfigState
        ) {
            val s = AppLanguageController.currentStrings()
            val isVideoOnlyPrep = prepConfig.mode == ProcessingMode.VIDEO_ONLY_CRF
            val prepTitle = if (isVideoOnlyPrep) s.turboVideoOnlyPrepModeTitle else s.turboVideoAudioPrepModeTitle
            val prepFileName = if (isVideoOnlyPrep) "turbo_video_only_prep.mp4" else "turbo_video_audio_prep.mp4"
            val summaryBanner = buildSummaryBanner(media, prepConfig, s)
            val presetLabel = resolvePresetDisplayLabel(prepConfig)
            val expectedDur = if (turboTargetConfig.mode == ProcessingMode.TRIM_VIDEO) {
                FfmpegMath.resolveTrimRange(turboTargetConfig, media.durationSec).third ?: media.durationSec
            } else {
                media.durationSec
            }
            val initialPrep = EncodingState.Running(
                modeTitle = prepTitle,
                outputFileName = prepFileName,
                passIndex = 1,
                totalPasses = 1,
                statusMessage = s.notifPreparing,
                currentTimeSec = 0.0,
                totalDurationSec = expectedDur,
                percent = if (expectedDur != null) 0 else null,
                speed = "...",
                sizeKb = 0L,
                logs = emptyList(),
                summaryBanner = summaryBanner,
                videoSpecsSummary = null,
                presetLabel = presetLabel,
                isHardwareTurbo = false,
                canSwitchToTurbo = false,
                isTurboPreRemux = true
            )
            EncodingJobController.startVideoAudioPrepThenTurbo(media, prepConfig, turboTargetConfig, initialPrep)
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_VIDEO_AUDIO_PREP_THEN_TURBO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelEncoding(context: Context) {
            val cancelledId = EncodingJobController.cancelActiveRun()
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_CANCEL
                if (cancelledId > 0L) {
                    putExtra(EXTRA_CANCELLED_RUN_ID, cancelledId)
                }
            }
            runCatching { context.startService(intent) }
        }

        private fun formatCompactDimension(width: Int, height: Int): String {
            val longEdge = maxOf(width, height)
            val shortEdge = minOf(width, height)
            return when {
                longEdge == 3840 && shortEdge == 2160 -> "4K"
                longEdge == 2560 && shortEdge == 1440 -> "1440p"
                longEdge == 1920 && shortEdge == 1080 -> "1080p"
                longEdge == 1280 && shortEdge == 720 -> "720p"
                longEdge in 848..854 && shortEdge == 480 -> "480p"
                else -> "${width}×${height}"
            }
        }

        internal fun formatCompactVideoSpecsParts(
            media: MediaInfo,
            config: ModeConfigState,
            isTurbo: Boolean,
            displayDimsOverride: Pair<Int, Int>? = null
        ): List<String> {
            val isTwoPass = config.mode == ProcessingMode.DISCORD_20MB ||
                config.mode == ProcessingMode.WHATSAPP_100MB ||
                config.mode == ProcessingMode.GMAIL_25MB ||
                config.mode == ProcessingMode.CUSTOM_SIZE
            val isTrimCrf = config.mode == ProcessingMode.TRIM_VIDEO &&
                (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY || config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC)
            val isVideoReencode = isTurbo || isTwoPass || isTrimCrf ||
                config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
                config.mode == ProcessingMode.VIDEO_AND_AUDIO_CRF
            val supportsFpsAndRes = isTwoPass || isTrimCrf

            val resPart = when {
                supportsFpsAndRes && config.resolution != com.example.domain.ResolutionOption.ORIGINAL -> {
                    when (config.resolution) {
                        com.example.domain.ResolutionOption.P2160 -> "4K"
                        com.example.domain.ResolutionOption.P1440 -> "1440p"
                        com.example.domain.ResolutionOption.P1080 -> "1080p"
                        com.example.domain.ResolutionOption.P720 -> "720p"
                        com.example.domain.ResolutionOption.P480 -> "480p"
                        com.example.domain.ResolutionOption.ORIGINAL -> null
                    }
                }
                isTurbo -> {
                    val (outW, outH) = Media3TurboEngine.resolveTargetDimensions(media, config, displayDimsOverride)
                    formatCompactDimension(outW, outH)
                }
                media.width != null && media.height != null -> formatCompactDimension(media.width, media.height)
                else -> null
            }

            val fpsPart = when {
                supportsFpsAndRes && config.fps == com.example.domain.FpsOption.FPS_30 -> "30fps"
                media.fps != null -> {
                    val rounded = kotlin.math.round(media.fps)
                    if (kotlin.math.abs(media.fps - rounded) < 0.08) "${rounded.toInt()}fps"
                    else "${String.format(java.util.Locale.US, "%.2f", media.fps).trimEnd('0').trimEnd('.')}fps"
                }
                else -> null
            }

            val bitPart = if (isVideoReencode) {
                if (config.bitDepth == com.example.domain.BitDepthOption.BIT_10) "10bit" else "8bit"
            } else {
                media.bitShort.ifBlank { "8bit" }
            }

            return listOfNotNull(resPart, fpsPart, bitPart)
        }

        /**
         * Unified Turbo strip format:
         * <20MB · H264 635k · AAC · 1080p · 30fps · 8bit
         */
        internal fun buildTurboUnifiedSpecs(
            media: MediaInfo,
            config: ModeConfigState,
            s: AppStrings,
            overrideVideoBitrateBps: Int? = null,
            displayDimsOverride: Pair<Int, Int>? = null
        ): String {
            val bps = overrideVideoBitrateBps ?: Media3TurboEngine.estimateInitialVideoBitrateBps(media, config)
            val kbps = (bps / 1000).coerceAtLeast(100)
            val wantsAudioCopy = config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
                (config.mode == ProcessingMode.TRIM_VIDEO && config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY)
            val audioPart = when {
                !media.hasAudio -> s.bannerSilentAudio
                wantsAudioCopy -> if (s.language == com.example.domain.AppLanguage.TR) "Ses copy" else "Audio copy"
                else -> "AAC"
            }
            val tailSpecs = formatCompactVideoSpecsParts(
                media = media,
                config = config,
                isTurbo = true,
                displayDimsOverride = displayDimsOverride
            )

            val headParts = when (config.mode) {
                ProcessingMode.DISCORD_20MB -> listOf("<20MB", "${config.codec.tag} ${kbps}k", audioPart)
                ProcessingMode.WHATSAPP_100MB -> listOf("<100MB", "${config.codec.tag} ${kbps}k", audioPart)
                ProcessingMode.GMAIL_25MB -> listOf("<25MB", "${config.codec.tag} ${kbps}k", audioPart)
                ProcessingMode.CUSTOM_SIZE -> {
                    val mb = FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
                    listOf("<${FfmpegMath.formatMbTag(mb)}MB", "${config.codec.tag} ${kbps}k", audioPart)
                }
                ProcessingMode.TRIM_VIDEO -> {
                    val (startSec, endSec, _) = FfmpegMath.resolveTrimPreviewSeconds(config, media.durationSec)
                    val startLabel = startSec?.let { FfmpegMath.formatKeyframeTime(it, s) } ?: "00:00"
                    val endLabel = endSec?.let { FfmpegMath.formatKeyframeTime(it, s) } ?: s.trimEndPlaceholder
                    listOf("$startLabel→$endLabel", "${config.codec.tag} ${kbps}k", audioPart)
                }
                else -> listOf("${config.codec.tag} ${kbps}k", audioPart)
            }

            return (headParts + tailSpecs).joinToString(" · ")
        }

        /**
         * Unified plain-text summary line for FFmpeg processing screen matching the exact Turbo order:
         * <20MB · H264 635k · AAC · 1080p · 30 FPS · 8b
         * (Preset is already shown in Playfair italic beneath the percentage, so it is never repeated here.)
         */
        internal fun buildSummaryBanner(media: MediaInfo, config: ModeConfigState, s: AppStrings): String {
            val tailSpecs = if (media.hasVideo &&
                config.mode != ProcessingMode.EXTRACT_AUDIO &&
                config.mode != ProcessingMode.CUSTOM_COMMAND
            ) {
                formatCompactVideoSpecsParts(media, config, isTurbo = false)
            } else {
                emptyList()
            }

            val audioPart = if (!media.hasAudio) s.bannerSilentAudio else "AAC"
            val audioCopyPart = if (!media.hasAudio) s.bannerSilentAudio else {
                if (s.language == com.example.domain.AppLanguage.TR) "Ses copy" else "Audio copy"
            }

            val headParts: List<String> = when (config.mode) {
                ProcessingMode.DISCORD_20MB,
                ProcessingMode.WHATSAPP_100MB,
                ProcessingMode.GMAIL_25MB,
                ProcessingMode.CUSTOM_SIZE -> {
                    val mb = when (config.mode) {
                        ProcessingMode.DISCORD_20MB -> 20.0
                        ProcessingMode.WHATSAPP_100MB -> 100.0
                        ProcessingMode.GMAIL_25MB -> 25.0
                        else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
                    }
                    val dur = media.durationSec ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput) ?: 1.0
                    val defAb = FfmpegMath.defaultAudioBitrateForTwoPass(mb)
                    val reqAb = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
                    val plan = FfmpegMath.calculateAdaptiveTwoPassPlan(
                        targetMb = mb,
                        durationSec = dur,
                        audioBitrateKbps = reqAb,
                        codec = config.codec,
                        width = media.width,
                        height = media.height,
                        resolution = config.resolution,
                        fpsOption = config.fps,
                        sourceFps = media.fps
                    )
                    listOf("<${FfmpegMath.formatMbTag(mb)}MB", "${config.codec.tag} ${plan.videoBitrateKbps}k", audioPart)
                }
                ProcessingMode.TRIM_VIDEO -> {
                    val (startSec, endSec, _) = FfmpegMath.resolveTrimPreviewSeconds(config, media.durationSec)
                    val startLabel = startSec?.let { FfmpegMath.formatKeyframeTime(it, s) } ?: "00:00"
                    val endLabel = endSec?.let { FfmpegMath.formatKeyframeTime(it, s) } ?: s.trimEndPlaceholder
                    val range = "$startLabel→$endLabel"
                    when (config.trimSubMode) {
                        TrimSubMode.CRF_AUDIO_COPY -> listOf(range, "${config.codec.tag} CRF ${config.codec.crf}", audioCopyPart)
                        TrimSubMode.CRF_AUDIO_AAC -> listOf(range, "${config.codec.tag} CRF ${config.codec.crf}", audioPart)
                        else -> listOf(range, config.trimSubMode.localizedTitle(s))
                    }
                }
                ProcessingMode.REMUX_FORMAT -> listOf(s.bannerRemux)
                ProcessingMode.AUDIO_ONLY_AAC -> {
                    val ab = FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, 256)
                    listOf(s.bannerAudioOnly(ab))
                }
                ProcessingMode.VIDEO_ONLY_CRF ->
                    listOf("${config.codec.tag} CRF ${config.codec.crf}", audioCopyPart)
                ProcessingMode.VIDEO_AND_AUDIO_CRF ->
                    listOf("${config.codec.tag} CRF ${config.codec.crf}", audioPart)
                ProcessingMode.EXTRACT_AUDIO -> listOf(s.bannerExtractAudio(config.audioExtractFormat.localizedLabel(s)))
                ProcessingMode.CUSTOM_COMMAND -> listOf(s.bannerCustomCmd)
            }

            return (headParts + tailSpecs).joinToString(" · ")
        }

        internal fun resolvePresetDisplayLabel(config: ModeConfigState): String? {
            val usesPreset = when (config.mode) {
                ProcessingMode.DISCORD_20MB,
                ProcessingMode.WHATSAPP_100MB,
                ProcessingMode.GMAIL_25MB,
                ProcessingMode.CUSTOM_SIZE,
                ProcessingMode.VIDEO_ONLY_CRF,
                ProcessingMode.VIDEO_AND_AUDIO_CRF -> true
                ProcessingMode.TRIM_VIDEO -> config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY ||
                    config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC
                else -> false
            }
            if (!usesPreset) return null
            return when (config.preset) {
                com.example.domain.PresetOption.FAST -> "Fast"
                com.example.domain.PresetOption.SLOW -> "Slow"
                com.example.domain.PresetOption.VERYSLOW -> "Very Slow"
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var activeJob: Job? = null
    private lateinit var backgroundGuard: BackgroundExecutionGuard
    private lateinit var cpuBooster: CpuPerformanceBooster
    private val lastNotifyElapsedMs = AtomicLong(0L)

    @Volatile
    private var lastNotification: Notification? = null

    @Volatile
    private var activeRunOwnerId: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguageController.init(applicationContext)
        backgroundGuard = BackgroundExecutionGuard(applicationContext)
        cpuBooster = CpuPerformanceBooster(applicationContext)
        createNotificationChannel()
    }

    private fun teardownIfStillOwner(runId: Long, startId: Int) {
        synchronized(lifecycleLock) {
            val stillOwner = activeRunOwnerId == runId &&
                (EncodingJobController.isRunActive(runId) || EncodingJobController.currentActiveRunId() == 0L)
            if (!stillOwner) return
            activeRunOwnerId = 0L
            lastNotification = null
            cpuBooster.stop()
            backgroundGuard.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ (API 35+) enforces a cumulative 6-hour limit on mediaProcessing / dataSync FGS.
        // If a marathon encode is still running, transition cleanly to mediaPlayback (no 6h cap)
        // instead of letting the OS throw RemoteServiceException.
        val fallbackType = BackgroundExecutionPolicy.resolveTimeoutFallbackType(Build.VERSION.SDK_INT, fgsType)
        val hasActiveWork = EncodingJobController.currentActiveRunId() > 0L
        val notif = lastNotification
        if (hasActiveWork && fallbackType != null && notif != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transitioned = runCatching {
                startForeground(NOTIFICATION_ID, notif, fallbackType)
                true
            }.getOrDefault(false)
            if (transitioned) {
                backgroundGuard.ensureAlive()
                return
            }
        }
        synchronized(lifecycleLock) {
            activeRunOwnerId = 0L
            lastNotification = null
            EncodingJobController.cancelActiveRun()
            activeJob?.cancel()
            activeJob = null
            cpuBooster.stop()
            backgroundGuard.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If the user swipes the app card away or OEM task cleanup runs during an active encode,
        // immediately re-assert foreground standing and background anti-freeze anchors.
        if (EncodingJobController.currentActiveRunId() > 0L) {
            lastNotification?.let { notif ->
                runCatching { startForegroundCompat(notif) }
            }
            if (::backgroundGuard.isInitialized) {
                backgroundGuard.ensureAlive()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                synchronized(lifecycleLock) {
                    val targetRunId = intent.getLongExtra(EXTRA_CANCELLED_RUN_ID, -1L)
                    val currentRunId = EncodingJobController.currentActiveRunId()
                    if (targetRunId == -1L || currentRunId == 0L || currentRunId == targetRunId) {
                        activeRunOwnerId = 0L
                        lastNotification = null
                        EncodingJobController.cancelActiveRun()
                        activeJob?.cancel()
                        activeJob = null
                        cpuBooster.stop()
                        backgroundGuard.stop()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
            ACTION_START -> {
                val jobData = EncodingJobController.pendingJob
                if (jobData == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                val initialPasses = when (jobData.config.mode) {
                    ProcessingMode.DISCORD_20MB,
                    ProcessingMode.WHATSAPP_100MB,
                    ProcessingMode.GMAIL_25MB,
                    ProcessingMode.CUSTOM_SIZE -> 2
                    else -> 1
                }
                val previousJob = synchronized(lifecycleLock) {
                    activeRunOwnerId = jobData.runId
                    startForegroundCompat(
                        buildNotification(
                            title = "FFmpeg · ${jobData.config.mode.localizedTitle(s)}",
                            text = s.notifPreparing,
                            percent = 0,
                            passIndex = 1,
                            totalPasses = initialPasses
                        )
                    )
                    backgroundGuard.start(serviceScope) { refreshNotificationIfSilent() }
                    val prev = activeJob
                    prev?.cancel()
                    prev
                }
                activeJob = serviceScope.launch {
                    if (previousJob != null) {
                        withTimeoutOrNull(450L) { runCatching { previousJob.cancelAndJoin() } }
                    }
                    FfmpegEngine.awaitActiveSessionTermination(timeoutMs = 5_000L)
                    if (!EncodingJobController.isRunActive(jobData.runId)) {
                        teardownIfStillOwner(jobData.runId, startId)
                        return@launch
                    }
                    runEncodingPipeline(jobData.runId, startId, jobData.media, jobData.config)
                }
            }
            ACTION_START_TURBO -> {
                val jobData = EncodingJobController.pendingJob
                if (jobData == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                val previousJob = synchronized(lifecycleLock) {
                    activeRunOwnerId = jobData.runId
                    startForegroundCompat(
                        buildNotification(
                            title = "${s.turboNotifTitle} · ${jobData.config.mode.localizedTitle(s)}",
                            text = s.turboStatusPreparing,
                            percent = 0,
                            passIndex = 1,
                            totalPasses = 1
                        )
                    )
                    backgroundGuard.start(serviceScope) { refreshNotificationIfSilent() }
                    cpuBooster.stop()
                    val prev = activeJob
                    prev?.cancel()
                    prev
                }
                val handoffStartMs = SystemClock.elapsedRealtime()
                activeJob = serviceScope.launch {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
                    // 1. Wait until cancelled FFmpeg coroutine AND native FFmpegKit/libx264 C++
                    //    worker threads have 100% exited, freed 4K lookahead RAM, and closed their
                    //    SAF file descriptor before MediaCodec touches the file or Qualcomm VPU.
                    if (previousJob != null) {
                        withTimeoutOrNull(450L) { runCatching { previousJob.cancelAndJoin() } }
                    }
                    FfmpegEngine.awaitActiveSessionTermination(timeoutMs = 5_000L)

                    // 2. Wait until the 880ms 120Hz vertical slide animation into the speedometer
                    //    screen has cleanly settled so Adreno GPU & VPU initialize without rush
                    //    or contention, while the user calmly sees the Preparing state.
                    val elapsedMs = SystemClock.elapsedRealtime() - handoffStartMs
                    val settleRemainingMs = (1_050L - elapsedMs).coerceAtLeast(0L)
                    if (settleRemainingMs > 0L) {
                        delay(settleRemainingMs)
                    }
                    if (!EncodingJobController.isRunActive(jobData.runId)) {
                        teardownIfStillOwner(jobData.runId, startId)
                        return@launch
                    }
                    runTurboPipeline(
                        runId = jobData.runId,
                        startId = startId,
                        media = jobData.media,
                        config = jobData.config,
                        inputUriOverride = null,
                        hasTriedRemux = false,
                        extraDirToCleanup = null
                    )
                }
            }
            ACTION_REMUX_THEN_TURBO -> {
                val jobData = EncodingJobController.pendingJob
                if (jobData == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                val previousJob = synchronized(lifecycleLock) {
                    activeRunOwnerId = jobData.runId
                    startForegroundCompat(
                        buildNotification(
                            title = "FFmpeg · ${s.turboRemuxModeTitle}",
                            text = s.turboRemuxStepStatus,
                            percent = 0,
                            passIndex = 1,
                            totalPasses = 1
                        )
                    )
                    backgroundGuard.start(serviceScope) { refreshNotificationIfSilent() }
                    val prev = activeJob
                    prev?.cancel()
                    prev
                }
                activeJob = serviceScope.launch {
                    if (previousJob != null) {
                        withTimeoutOrNull(450L) { runCatching { previousJob.cancelAndJoin() } }
                    }
                    FfmpegEngine.awaitActiveSessionTermination(timeoutMs = 5_000L)
                    if (!EncodingJobController.isRunActive(jobData.runId)) {
                        teardownIfStillOwner(jobData.runId, startId)
                        return@launch
                    }
                    runRemuxThenTurboPipeline(jobData.runId, startId, jobData.media, jobData.config)
                }
            }
            ACTION_AUDIO_CONVERT_THEN_TURBO -> {
                val jobData = EncodingJobController.pendingJob
                if (jobData == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                val audioKbps = (jobData.audioConvertBitrateKbps
                    ?: Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(jobData.media))
                    .coerceIn(32, 768)
                val previousJob = synchronized(lifecycleLock) {
                    activeRunOwnerId = jobData.runId
                    startForegroundCompat(
                        buildNotification(
                            title = "FFmpeg · ${s.turboAudioConvertModeTitle}",
                            text = s.turboAudioConvertStepStatus(audioKbps),
                            percent = 0,
                            passIndex = 1,
                            totalPasses = 1
                        )
                    )
                    backgroundGuard.start(serviceScope) { refreshNotificationIfSilent() }
                    val prev = activeJob
                    prev?.cancel()
                    prev
                }
                activeJob = serviceScope.launch {
                    if (previousJob != null) {
                        withTimeoutOrNull(450L) { runCatching { previousJob.cancelAndJoin() } }
                    }
                    FfmpegEngine.awaitActiveSessionTermination(timeoutMs = 5_000L)
                    if (!EncodingJobController.isRunActive(jobData.runId)) {
                        teardownIfStillOwner(jobData.runId, startId)
                        return@launch
                    }
                    runAudioConvertThenTurboPipeline(
                        runId = jobData.runId,
                        startId = startId,
                        media = jobData.media,
                        config = jobData.config,
                        audioBitrateKbps = audioKbps
                    )
                }
            }
            ACTION_VIDEO_AUDIO_PREP_THEN_TURBO -> {
                val jobData = EncodingJobController.pendingJob
                val turboTarget = jobData?.turboTargetConfig
                if (jobData == null || turboTarget == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                val prepTitle = if (jobData.config.mode == ProcessingMode.VIDEO_ONLY_CRF) {
                    s.turboVideoOnlyPrepModeTitle
                } else {
                    s.turboVideoAudioPrepModeTitle
                }
                val previousJob = synchronized(lifecycleLock) {
                    activeRunOwnerId = jobData.runId
                    startForegroundCompat(
                        buildNotification(
                            title = "FFmpeg · $prepTitle",
                            text = s.notifPreparing,
                            percent = 0,
                            passIndex = 1,
                            totalPasses = 1
                        )
                    )
                    backgroundGuard.start(serviceScope) { refreshNotificationIfSilent() }
                    val prev = activeJob
                    prev?.cancel()
                    prev
                }
                activeJob = serviceScope.launch {
                    if (previousJob != null) {
                        withTimeoutOrNull(450L) { runCatching { previousJob.cancelAndJoin() } }
                    }
                    FfmpegEngine.awaitActiveSessionTermination(timeoutMs = 5_000L)
                    if (!EncodingJobController.isRunActive(jobData.runId)) {
                        teardownIfStillOwner(jobData.runId, startId)
                        return@launch
                    }
                    runVideoAudioPrepThenTurboPipeline(
                        runId = jobData.runId,
                        startId = startId,
                        media = jobData.media,
                        prepConfig = jobData.config,
                        turboTargetConfig = turboTarget
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runEncodingPipeline(
        runId: Long,
        startId: Int,
        media: MediaInfo,
        config: ModeConfigState
    ) {
        cpuBooster.start(CpuPerformanceBooster.BoostMode.FFMPEG_SOFTWARE, serviceScope)
        val s = AppLanguageController.currentStrings()
        val workDir = FfmpegEngine.createWorkDir(applicationContext)
        val collectedLogs = mutableListOf<String>()
        var logsSnapshot: List<String> = emptyList()
        val modeLocalizedTitle = config.mode.localizedTitle(s)
        val modeTitle = "${config.mode.formattedNumber} · $modeLocalizedTitle"
        val expectedOutputName = FfmpegMath.computeOutputFileName(media, config, s)
        val summaryBanner = buildSummaryBanner(media, config, s)
        val videoSpecsSummary: String? = null
        val presetLabel = resolvePresetDisplayLabel(config)
        val canSwitchToTurbo = supportsMedia3HardwareTurbo(media, config)

        fun appendLog(line: String) {
            synchronized(collectedLogs) {
                if (collectedLogs.lastOrNull() == line) return
                if (collectedLogs.size >= 80) collectedLogs.removeAt(0)
                collectedLogs.add(line)
                logsSnapshot = collectedLogs.toList()
            }
        }

        fun currentLogs(): List<String> = synchronized(collectedLogs) { logsSnapshot }

        val simdSummary = CpuSimdProfile.detect(NativeCpuAffinity::getArm64SimdMask).summaryLabel()
        val runtimeFfmpegVer = FfmpegEngine.getRuntimeFfmpegVersion()
        val versionSuffix = runtimeFfmpegVer?.let { " (v$it)" }.orEmpty()
        appendLog("${s.logTagStarted} $modeTitle — $summaryBanner")
        appendLog("[FFmpeg] ${BuildConfig.FFMPEG_ENGINE_VARIANT}$versionSuffix · CPU: $simdSummary")
        if (config.mode != ProcessingMode.REMUX_FORMAT &&
            config.mode != ProcessingMode.EXTRACT_AUDIO &&
            config.mode != ProcessingMode.AUDIO_ONLY_AAC &&
            config.mode != ProcessingMode.CUSTOM_COMMAND
        ) {
            val encoderSimdLine = FfmpegEngine.probeActiveEncoderSimd().formatCompactLine()
            if (encoderSimdLine != null) {
                appendLog("[SIMD] $encoderSimdLine")
            }
        }

        try {
            if (config.mode == ProcessingMode.CUSTOM_COMMAND) {
                runCustomCommandMode(
                    runId = runId,
                    media = media,
                    config = config,
                    workDir = workDir,
                    modeTitle = modeTitle,
                    summaryBanner = summaryBanner,
                    s = s,
                    currentLogs = ::currentLogs,
                    appendLog = ::appendLog
                )
                return
            }

            val effectiveConfig = resolveAutoTrimKeyframesIfNeeded(media, config)
            if (!EncodingJobController.isRunActive(runId)) return

            val tempOutFile = File(workDir, expectedOutputName)
            val passLogPrefix = File(workDir, "ff2pass").absolutePath
            val inputPlaceholder = "__FFMPEG_LAZY_INPUT__"
            val steps = FfmpegMath.buildFfmpegSteps(
                media = media,
                config = effectiveConfig,
                inputTokenProvider = { inputPlaceholder },
                outputPath = tempOutFile.absolutePath,
                passLogPrefix = passLogPrefix,
                strings = s
            )

            for ((idx, step) in steps.withIndex()) {
                if (!EncodingJobController.isRunActive(runId)) return
                val passNum = idx + 1
                val totalPasses = steps.size
                if (totalPasses > 1) {
                    appendLog("${s.logTagStep(passNum, totalPasses)} ${step.label}")
                }

                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Running(
                        modeTitle = modeTitle,
                        outputFileName = expectedOutputName,
                        passIndex = passNum,
                        totalPasses = totalPasses,
                        statusMessage = step.label,
                        currentTimeSec = 0.0,
                        totalDurationSec = step.expectedDurationSec,
                        percent = if (step.expectedDurationSec != null) 0 else null,
                        speed = "...",
                        sizeKb = 0L,
                        logs = currentLogs(),
                        summaryBanner = summaryBanner,
                        videoSpecsSummary = videoSpecsSummary,
                        presetLabel = presetLabel,
                        canSwitchToTurbo = canSwitchToTurbo
                    )
                )
                updateNotificationThrottled(
                    runId = runId,
                    title = "FFmpeg · $modeLocalizedTitle",
                    text = step.label,
                    percent = 0,
                    passIndex = passNum,
                    totalPasses = totalPasses,
                    force = true
                )

                val stepArgs = step.args.map { arg ->
                    if (arg == inputPlaceholder) {
                        FfmpegEngine.resolveInputPath(applicationContext, media.uri)
                    } else {
                        arg
                    }
                }

                var lastUiEmitMs = 0L
                var lastEmittedPct: Int? = null
                var lastEmittedWholeSec = -1L
                var lastEmittedTenthMb = -1L
                var lastLogEmitMs = 0L

                cpuBooster.scheduleEarlyThreadDiscovery(serviceScope)
                val stepResult = FfmpegEngine.executeStep(
                    args = stepArgs,
                    expectedDurationSec = step.expectedDurationSec,
                    onProgress = { curSec, pct, spd, kb ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        cpuBooster.pulseIfDue()
                        val liveStrings = AppLanguageController.currentStrings()
                        val nowMs = SystemClock.elapsedRealtime()
                        val wholeSec = kotlin.math.floor(curSec.coerceAtLeast(0.0)).toLong()
                        val tenthMb = (kb * 10L) / 1024L
                        val pctChanged = pct != lastEmittedPct
                        val visualChanged = pctChanged || wholeSec != lastEmittedWholeSec || tenthMb != lastEmittedTenthMb
                        if (pctChanged || (visualChanged && nowMs - lastUiEmitMs >= 250L)) {
                            lastUiEmitMs = nowMs
                            lastEmittedPct = pct
                            lastEmittedWholeSec = wholeSec
                            lastEmittedTenthMb = tenthMb
                            EncodingJobController.updateStateIfActive(
                                runId,
                                EncodingState.Running(
                                    modeTitle = modeTitle,
                                    outputFileName = expectedOutputName,
                                    passIndex = passNum,
                                    totalPasses = totalPasses,
                                    statusMessage = step.label,
                                    currentTimeSec = wholeSec.toDouble(),
                                    totalDurationSec = step.expectedDurationSec,
                                    percent = pct,
                                    speed = spd,
                                    sizeKb = kb,
                                    logs = currentLogs(),
                                    summaryBanner = summaryBanner,
                                    videoSpecsSummary = videoSpecsSummary,
                                    presetLabel = presetLabel,
                                    canSwitchToTurbo = canSwitchToTurbo
                                )
                            )
                        }
                        val notifText = FfmpegMath.formatTelemetryLine(
                            currentSec = wholeSec.toDouble(),
                            totalDurationSec = step.expectedDurationSec,
                            percent = pct,
                            speed = spd,
                            sizeKb = kb,
                            includePercent = false,
                            strings = liveStrings
                        )
                        val titlePrefix = if (totalPasses > 1) "($passNum/$totalPasses) " else ""
                        updateNotificationThrottled(
                            runId = runId,
                            title = "FFmpeg · $titlePrefix${config.mode.localizedTitle(liveStrings)}",
                            text = notifText,
                            percent = pct,
                            passIndex = passNum,
                            totalPasses = totalPasses,
                            force = false
                        )
                    },
                    onLogLine = { line ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        appendLog(line)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastLogEmitMs >= 320L) {
                            lastLogEmitMs = nowMs
                            val snapshot = currentLogs()
                            val cur = EncodingJobController.state.value
                            if (cur is EncodingState.Running && cur.logs !== snapshot) {
                                EncodingJobController.updateStateIfActive(
                                    runId,
                                    cur.copy(logs = snapshot)
                                )
                            }
                        }
                    }
                )

                if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                    return
                }

                if (!stepResult.success) {
                    val curS = AppLanguageController.currentStrings()
                    val errMsg = curS.errFfmpegExitCode(stepResult.returnCode)
                    appendLog("${curS.logTagError} $errMsg")
                    val hint = resolveContextualHint(config, curS)
                    val snapLogs = currentLogs()
                    EncodingJobController.updateStateIfActive(
                        runId,
                        EncodingState.Failed(
                            modeTitle = modeTitle,
                            errorMessage = errMsg,
                            hint = hint,
                            logs = snapLogs,
                            wasCancelled = false,
                            rawErrorDetails = FfmpegMath.buildAiRawErrorReport(
                                modeTitle = modeTitle,
                                errorMessage = errMsg,
                                mediaFileName = media.fileName,
                                mediaSummary = describeMediaForErrorReport(media),
                                executedCommand = FfmpegMath.formatCommandForErrorReport(
                                    toolBinary = "ffmpeg",
                                    args = step.args,
                                    mediaExtension = media.extension
                                ),
                                returnCode = stepResult.returnCode,
                                rawOutput = stepResult.rawOutput,
                                logs = snapLogs
                            )
                        )
                    )
                    return
                }
            }

            if (!EncodingJobController.isRunActive(runId)) return

            if (!tempOutFile.exists() || tempOutFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                appendLog("${curS.logTagError} ${curS.errOutputNotCreated}")
                val snapLogs = currentLogs()
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.errOutputNotCreated,
                        hint = resolveContextualHint(config, curS),
                        logs = snapLogs,
                        rawErrorDetails = FfmpegMath.buildAiRawErrorReport(
                            modeTitle = modeTitle,
                            errorMessage = curS.errOutputNotCreated,
                            mediaFileName = media.fileName,
                            mediaSummary = describeMediaForErrorReport(media),
                            executedCommand = steps.lastOrNull()?.let {
                                FfmpegMath.formatCommandForErrorReport("ffmpeg", it.args, media.extension)
                            },
                            logs = snapLogs
                        )
                    )
                )
                return
            }

            // Publish cleanly to MediaStore (Movies/FFmpeg or Music/FFmpeg)
            val curS = AppLanguageController.currentStrings()
            val saved = MediaStoreSaver.saveToPublicStorage(
                context = applicationContext,
                sourceFile = tempOutFile,
                desiredFileName = expectedOutputName
            )
            val completed = CompletedOutput(
                id = UUID.randomUUID().toString(),
                fileName = expectedOutputName,
                displayPath = saved.displayPath,
                contentUriString = saved.uri.toString(),
                mimeType = saved.mimeType,
                outputSizeBytes = saved.sizeBytes,
                inputSizeBytes = media.sizeBytes,
                modeLabel = config.mode.localizedTitle(curS),
                timestampMs = System.currentTimeMillis()
            )
            runCatching { PreferencesRepository(applicationContext).appendHistory(listOf(completed)) }

            appendLog("${curS.logTagCompleted} ${completed.fileName} (${FfmpegMath.formatSizeBytes(saved.sizeBytes)})")
            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Success(
                    outputs = listOf(completed),
                    modeTitle = modeTitle,
                    summaryMessage = curS.successOutputSize(FfmpegMath.formatSizeBytes(saved.sizeBytes)),
                    logs = currentLogs()
                )
            )
        } catch (_: CancellationException) {
            // Cancelled cleanly: keep state Idle and let finally clean resources
        } catch (t: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                val errMsg = t.message ?: curS.errUnexpected
                appendLog("${curS.logTagError} $errMsg")
                val snapLogs = currentLogs()
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = errMsg,
                        hint = resolveContextualHint(config, curS),
                        logs = snapLogs,
                        rawErrorDetails = FfmpegMath.buildAiRawErrorReport(
                            modeTitle = modeTitle,
                            errorMessage = errMsg,
                            mediaFileName = media.fileName,
                            mediaSummary = describeMediaForErrorReport(media),
                            rawOutput = t.stackTraceToString().take(4_000),
                            logs = snapLogs
                        )
                    )
                )
            }
        } finally {
            // Zero-bloat guarantee: always wipe temp workDir (.log, .cutree, .mbtree, partial outputs)
            runCatching { workDir.deleteRecursively() }
            teardownIfStillOwner(runId, startId)
        }
    }

    private data class TrimPreCutOutcome(
        val bracket: FfmpegMath.TrimPreCutBracket,
        val preCutFile: File,
        val preCutTiming: FfmpegEngine.ClipTimingProbe,
        val adjustedTargetConfig: ModeConfigState
    )

    /**
     * Automatically scans safe bracketing keyframes (`safeStartKf <= origStart`,
     * `safeEndKf >= origEnd + 0.15s`) when Turbo recovery runs for a [ProcessingMode.TRIM_VIDEO]
     * sub-range so we never process a 2-hour movie just to export a short clip.
     */
    private suspend fun resolveTrimPreCutBracketIfApplicable(
        media: MediaInfo,
        targetConfig: ModeConfigState
    ): FfmpegMath.TrimPreCutBracket? {
        if (!media.hasVideo || !FfmpegMath.shouldPreCutTrimForTurboPrep(targetConfig, media.durationSec)) {
            return null
        }
        val (previewStart, previewEnd, _) = FfmpegMath.resolveTrimPreviewSeconds(targetConfig, media.durationSec)
        val origStart = previewStart?.coerceAtLeast(0.0) ?: 0.0
        val origEnd = previewEnd?.let { endVal ->
            if (media.durationSec != null && media.durationSec > 0.0) endVal.coerceAtMost(media.durationSec) else endVal
        }
        if (origEnd != null && origEnd <= origStart + 0.05) return null

        val cachedStartScan = targetConfig.trimScan.startScan?.takeIf {
            kotlin.math.abs(it.targetSec - origStart) <= 0.05
        }
        val cachedEndScan = origEnd?.let { en ->
            targetConfig.trimScan.endScan?.takeIf { kotlin.math.abs(it.targetSec - en) <= 0.05 }
        }
        val needStartScan = origStart > 0.05 && cachedStartScan == null
        val endTargetForScan = origEnd?.takeIf {
            cachedEndScan == null && (media.durationSec == null || it < media.durationSec - 0.5)
        }

        return try {
            // Run start/end FFprobe scans sequentially to avoid concurrent SAF FD contention on Android 16.
            val startScanResult = cachedStartScan ?: if (needStartScan) {
                FfmpegEngine.scanKeyframes(
                    context = applicationContext,
                    uri = media.uri,
                    targetSec = origStart,
                    isStart = true,
                    durationSec = media.durationSec
                )
            } else null
            val endScanResult = cachedEndScan ?: if (endTargetForScan != null) {
                FfmpegEngine.scanKeyframes(
                    context = applicationContext,
                    uri = media.uri,
                    targetSec = endTargetForScan,
                    isStart = false,
                    durationSec = media.durationSec
                )
            } else null
            FfmpegMath.resolveTrimPreCutBracket(
                origStartSec = origStart,
                origEndSec = origEnd,
                mediaDurationSec = media.durationSec,
                startScan = startScanResult,
                endScan = endScanResult
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Silently slices ONLY `[safeStartKf, safeEndKf]` losslessly into a universal `.mkv` clip
     * (`-noaccurate_seek -c copy -avoid_negative_ts make_zero`) before Audio-Only / Video-Only /
     * Video+Audio Turbo preparation, keeping video & audio packets 100% locked together and
     * verifying millimetric PTS alignment via [FfmpegEngine.probeClipTiming].
     * Falls back cleanly to `null` (full source) if probing or slicing is not viable.
     */
    private suspend fun executeSilentLosslessTrimPreCut(
        runId: Long,
        media: MediaInfo,
        targetConfig: ModeConfigState,
        workDir: File,
        modeTitle: String,
        summaryBanner: String,
        presetLabel: String?,
        appendLog: (String) -> Unit,
        currentLogs: () -> List<String>
    ): TrimPreCutOutcome? {
        val bracket = resolveTrimPreCutBracketIfApplicable(media, targetConfig) ?: return null
        if (!EncodingJobController.isRunActive(runId)) return null

        val preCutFile = File(workDir, "turbo_trim_precut.mkv")
        runCatching { if (preCutFile.exists()) preCutFile.delete() }
        val s = AppLanguageController.currentStrings()
        appendLog("${s.logTagStarted} ${s.turboTrimPreCutStatus}")

        EncodingJobController.updateStateIfActive(
            runId,
            EncodingState.Running(
                modeTitle = modeTitle,
                outputFileName = preCutFile.name,
                passIndex = 1,
                totalPasses = 1,
                statusMessage = s.turboTrimPreCutStatus,
                currentTimeSec = 0.0,
                totalDurationSec = bracket.expectedSpanSec,
                percent = 0,
                speed = "...",
                sizeKb = 0L,
                logs = currentLogs(),
                summaryBanner = summaryBanner,
                videoSpecsSummary = null,
                presetLabel = presetLabel,
                isHardwareTurbo = false,
                canSwitchToTurbo = false,
                isTurboPreRemux = true
            )
        )

        val inputPath = FfmpegEngine.resolveInputPath(applicationContext, media.uri)
        val preCutArgs = FfmpegMath.buildTrimPreCutArgs(
            inputPath = inputPath,
            outputPath = preCutFile.absolutePath,
            bracket = bracket,
            hasAudio = media.hasAudio
        )
        val stepResult = FfmpegEngine.executeStep(
            args = preCutArgs,
            expectedDurationSec = bracket.expectedSpanSec,
            onProgress = { _, _, _, _ ->
                if (EncodingJobController.isRunActive(runId)) {
                    cpuBooster.pulseIfDue()
                }
            },
            onLogLine = { line ->
                if (EncodingJobController.isRunActive(runId)) {
                    appendLog(line)
                }
            }
        )
        if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
            runCatching { preCutFile.delete() }
            return null
        }
        if (!stepResult.success || !preCutFile.exists() || preCutFile.length() == 0L) {
            runCatching { preCutFile.delete() }
            return null
        }

        val timing = FfmpegEngine.probeClipTiming(preCutFile)
        val adjustedCfg = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
            originalTargetConfig = targetConfig,
            bracket = bracket,
            preparedDurationSec = timing.durationSec,
            preparedFirstVideoPtsSec = timing.firstVideoPtsSec
        )
        if (adjustedCfg == null) {
            runCatching { preCutFile.delete() }
            return null
        }
        return TrimPreCutOutcome(
            bracket = bracket,
            preCutFile = preCutFile,
            preCutTiming = timing,
            adjustedTargetConfig = adjustedCfg
        )
    }

    /**
     * One-time automatic lossless MP4 remux via FFmpeg (-c copy) followed immediately (without
     * asking the user again) by sliding back to the Media3 car speedometer screen to retry.
     * When trimming a sub-range, automatically slices only the safe keyframe bracket instead of
     * remuxing the entire movie, then adjusts the Media3 clip range millimetrically.
     */
    private suspend fun runRemuxThenTurboPipeline(
        runId: Long,
        startId: Int,
        media: MediaInfo,
        config: ModeConfigState
    ) {
        cpuBooster.start(CpuPerformanceBooster.BoostMode.FFMPEG_SOFTWARE, serviceScope)
        val s = AppLanguageController.currentStrings()
        val remuxWorkDir = FfmpegEngine.createWorkDir(applicationContext)
        val remuxFile = File(remuxWorkDir, "turbo_remux.mp4")
        val collectedLogs = mutableListOf<String>()
        var logsSnapshot: List<String> = emptyList()
        val modeLocalizedTitle = config.mode.localizedTitle(s)
        val modeTitle = "${config.mode.formattedNumber} · $modeLocalizedTitle"

        fun appendLog(line: String) {
            synchronized(collectedLogs) {
                if (collectedLogs.lastOrNull() == line) return
                if (collectedLogs.size >= 80) collectedLogs.removeAt(0)
                collectedLogs.add(line)
                logsSnapshot = collectedLogs.toList()
            }
        }

        fun currentLogs(): List<String> = synchronized(collectedLogs) { logsSnapshot }

        appendLog("${s.logTagStarted} ${s.turboRemuxModeTitle} — ${s.bannerRemux}")
        var handedOffToTurbo = false

        try {
            val preCutBracket = resolveTrimPreCutBracketIfApplicable(media, config)
            if (!EncodingJobController.isRunActive(runId)) return

            suspend fun executeRemuxAttempt(bracket: FfmpegMath.TrimPreCutBracket?): FfmpegStepResult {
                runCatching { if (remuxFile.exists()) remuxFile.delete() }
                val inputPath = FfmpegEngine.resolveInputPath(applicationContext, media.uri)
                val isHevc = FfmpegMath.isHevcCodec(media.videoCodec)
                val remuxArgs = if (bracket != null) {
                    FfmpegMath.buildTrimPreCutArgs(
                        inputPath = inputPath,
                        outputPath = remuxFile.absolutePath,
                        bracket = bracket,
                        hasAudio = media.hasAudio,
                        isHevcVideo = isHevc
                    )
                } else {
                    buildList {
                        addAll(listOf("-y", "-i", inputPath, "-map", "0:V:0"))
                        if (media.hasAudio) addAll(listOf("-map", "0:a:0?"))
                        addAll(listOf("-c", "copy", "-sn", "-dn"))
                        if (isHevc) addAll(listOf("-tag:v", "hvc1"))
                        addAll(
                            listOf(
                                "-avoid_negative_ts", "make_zero",
                                "-max_muxing_queue_size", "1024",
                                "-movflags", "+faststart",
                                remuxFile.absolutePath
                            )
                        )
                    }
                }
                val expectedDur = bracket?.expectedSpanSec ?: media.durationSec
                var lastUiEmitMs = 0L
                var lastEmittedPct: Int? = null
                var lastEmittedWholeSec = -1L
                var lastEmittedTenthMb = -1L
                var lastLogEmitMs = 0L

                return FfmpegEngine.executeStep(
                    args = remuxArgs,
                    expectedDurationSec = expectedDur,
                    onProgress = { curSec, pct, spd, kb ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        cpuBooster.pulseIfDue()
                        val liveS = AppLanguageController.currentStrings()
                        val nowMs = SystemClock.elapsedRealtime()
                        val wholeSec = kotlin.math.floor(curSec.coerceAtLeast(0.0)).toLong()
                        val tenthMb = (kb * 10L) / 1024L
                        val pctChanged = pct != lastEmittedPct
                        val visualChanged = pctChanged || wholeSec != lastEmittedWholeSec || tenthMb != lastEmittedTenthMb
                        if (pctChanged || (visualChanged && nowMs - lastUiEmitMs >= 250L)) {
                            lastUiEmitMs = nowMs
                            lastEmittedPct = pct
                            lastEmittedWholeSec = wholeSec
                            lastEmittedTenthMb = tenthMb
                            EncodingJobController.updateStateIfActive(
                                runId,
                                EncodingState.Running(
                                    modeTitle = liveS.turboRemuxModeTitle,
                                    outputFileName = remuxFile.name,
                                    passIndex = 1,
                                    totalPasses = 1,
                                    statusMessage = liveS.turboRemuxStepStatus,
                                    currentTimeSec = wholeSec.toDouble(),
                                    totalDurationSec = expectedDur,
                                    percent = pct,
                                    speed = spd,
                                    sizeKb = kb,
                                    logs = currentLogs(),
                                    summaryBanner = liveS.bannerRemux,
                                    videoSpecsSummary = null,
                                    presetLabel = null,
                                    isHardwareTurbo = false,
                                    canSwitchToTurbo = false,
                                    isTurboPreRemux = true
                                )
                            )
                        }
                        updateNotificationThrottled(
                            runId = runId,
                            title = "FFmpeg · ${liveS.turboRemuxModeTitle}",
                            text = FfmpegMath.formatTelemetryLine(wholeSec.toDouble(), expectedDur, pct, spd, kb, includePercent = false, strings = liveS),
                            percent = pct,
                            passIndex = 1,
                            totalPasses = 1,
                            force = false
                        )
                    },
                    onLogLine = { line ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        appendLog(line)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastLogEmitMs >= 320L) {
                            lastLogEmitMs = nowMs
                            val snapshot = currentLogs()
                            val cur = EncodingJobController.state.value
                            if (cur is EncodingState.Running && !cur.isHardwareTurbo && cur.logs !== snapshot) {
                                EncodingJobController.updateStateIfActive(
                                    runId,
                                    cur.copy(logs = snapshot)
                                )
                            }
                        }
                    }
                )
            }

            var stepResult = executeRemuxAttempt(preCutBracket)
            if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                return
            }

            var finalTargetConfig = config
            var finalRemuxMedia = media.copy(extension = "mp4")

            if (preCutBracket != null && stepResult.success && remuxFile.exists() && remuxFile.length() > 0L) {
                val timing = FfmpegEngine.probeClipTiming(remuxFile)
                val adjustedCfg = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
                    originalTargetConfig = config,
                    bracket = preCutBracket,
                    preparedDurationSec = timing.durationSec,
                    preparedFirstVideoPtsSec = timing.firstVideoPtsSec
                )
                if (adjustedCfg != null) {
                    finalTargetConfig = adjustedCfg
                    finalRemuxMedia = media.copy(
                        extension = "mp4",
                        durationSec = timing.durationSec ?: preCutBracket.expectedSpanSec ?: media.durationSec
                    )
                } else {
                    // Fallback to full-file remux if seek-cut duration didn't pass sanity verification
                    stepResult = executeRemuxAttempt(null)
                    if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                        return
                    }
                }
            }

            if (!stepResult.success || !remuxFile.exists() || remuxFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                val report = Media3CompatibilityAdvisor.diagnose(media, config, DeviceCodecInventory.queryCurrentDevice())
                val nextStep = Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                    report = report,
                    hasAudio = media.hasAudio,
                    errorCodeName = "REMUX_FAILED",
                    errorDetail = null,
                    hasTriedRemux = true,
                    hasTriedAudioConvert = false,
                    hasTriedVideoAudioConvert = false
                )
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.turboAfterRemuxFailedBody,
                        hint = curS.turboAfterRemuxFastHint,
                        logs = emptyList(),
                        wasCancelled = false,
                        isHardwareTurbo = true,
                        isUnsupportedFormat = true,
                        hasTriedRemux = true,
                        recoveryStep = nextStep,
                        diagnosisSummary = formatDiagnosisSummary(report, curS),
                        recommendedAudioBitrateKbps = report.recommendedAudioBitrateKbps,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = false,
                        hasTriedVideoAudioConvert = false
                    )
                )
                return
            }

            // Lossless remux succeeded! Slide automatically back to the speedometer UI (Slide 2)
            // without prompting the user again, and retry Media3 hardware encoding on the remuxed MP4.
            slideBackToTurboAndRun(
                runId = runId,
                startId = startId,
                media = finalRemuxMedia,
                config = finalTargetConfig,
                preparedFile = remuxFile,
                hasTriedRemux = true,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false,
                extraDirToCleanup = remuxWorkDir
            )
            handedOffToTurbo = true
        } catch (_: CancellationException) {
            // Cancelled cleanly
        } catch (_: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                val report = Media3CompatibilityAdvisor.diagnose(media, config, DeviceCodecInventory.queryCurrentDevice())
                val nextStep = Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                    report = report,
                    hasAudio = media.hasAudio,
                    errorCodeName = "REMUX_FAILED",
                    errorDetail = null,
                    hasTriedRemux = true,
                    hasTriedAudioConvert = false,
                    hasTriedVideoAudioConvert = false
                )
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.turboAfterRemuxFailedBody,
                        hint = curS.turboAfterRemuxFastHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = true,
                        hasTriedRemux = true,
                        recoveryStep = nextStep,
                        diagnosisSummary = formatDiagnosisSummary(report, curS),
                        recommendedAudioBitrateKbps = report.recommendedAudioBitrateKbps,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio
                    )
                )
            }
        } finally {
            if (!handedOffToTurbo) {
                runCatching { remuxWorkDir.deleteRecursively() }
                teardownIfStillOwner(runId, startId)
            }
        }
    }

    /**
     * Progressive Recovery Step 2: Keeps video 100% untouched (-c:v copy) while re-encoding only
     * the incompatible audio track into high-quality source-matched AAC (32..768k), then slides
     * automatically back to the Turbo speedometer screen and retries hardware acceleration.
     * When trimming a sub-range, silently pre-cuts ONLY the safe keyframe bracket losslessly first
     * so audio conversion runs in seconds with millimetric A/V sync (`aresample=async=1:first_pts=0`).
     */
    private suspend fun runAudioConvertThenTurboPipeline(
        runId: Long,
        startId: Int,
        media: MediaInfo,
        config: ModeConfigState,
        audioBitrateKbps: Int
    ) {
        cpuBooster.start(CpuPerformanceBooster.BoostMode.FFMPEG_SOFTWARE, serviceScope)
        val s = AppLanguageController.currentStrings()
        val audioWorkDir = FfmpegEngine.createWorkDir(applicationContext)
        val audioPrepFile = File(audioWorkDir, "turbo_audio_aac.mp4")
        val collectedLogs = mutableListOf<String>()
        var logsSnapshot: List<String> = emptyList()
        val modeLocalizedTitle = config.mode.localizedTitle(s)
        val modeTitle = "${config.mode.formattedNumber} · $modeLocalizedTitle"
        val safeAb = audioBitrateKbps.coerceIn(32, 768)

        fun appendLog(line: String) {
            synchronized(collectedLogs) {
                if (collectedLogs.lastOrNull() == line) return
                if (collectedLogs.size >= 80) collectedLogs.removeAt(0)
                collectedLogs.add(line)
                logsSnapshot = collectedLogs.toList()
            }
        }

        fun currentLogs(): List<String> = synchronized(collectedLogs) { logsSnapshot }

        appendLog("${s.logTagStarted} ${s.turboAudioConvertModeTitle} — ${s.bannerAudioOnly(safeAb)}")
        var handedOffToTurbo = false

        try {
            val preCut = executeSilentLosslessTrimPreCut(
                runId = runId,
                media = media,
                targetConfig = config,
                workDir = audioWorkDir,
                modeTitle = s.turboAudioConvertModeTitle,
                summaryBanner = s.bannerAudioOnly(safeAb),
                presetLabel = null,
                appendLog = ::appendLog,
                currentLogs = ::currentLogs
            )
            if (!EncodingJobController.isRunActive(runId)) return

            val inputPath = preCut?.preCutFile?.absolutePath
                ?: FfmpegEngine.resolveInputPath(applicationContext, media.uri)
            val expectedAudioDur = preCut?.preCutTiming?.durationSec
                ?: preCut?.bracket?.expectedSpanSec
                ?: media.durationSec

            val audioArgs = buildList {
                addAll(
                    listOf(
                        "-y",
                        "-i", inputPath,
                        "-map", "0:V:0",
                        "-map", "0:a:0",
                        "-c:v", "copy"
                    )
                )
                if (FfmpegMath.isHevcCodec(media.videoCodec)) {
                    addAll(listOf("-tag:v", "hvc1"))
                }
                addAll(
                    listOf(
                        "-c:a", "aac",
                        "-b:a", "${safeAb}k",
                        "-af", "aresample=async=1:first_pts=0",
                        "-sn", "-dn",
                        "-avoid_negative_ts", "make_zero",
                        "-max_muxing_queue_size", "1024",
                        "-movflags", "+faststart",
                        audioPrepFile.absolutePath
                    )
                )
            }

            var lastUiEmitMs = 0L
            var lastEmittedPct: Int? = null
            var lastEmittedWholeSec = -1L
            var lastEmittedTenthMb = -1L
            var lastLogEmitMs = 0L

            val stepResult = FfmpegEngine.executeStep(
                args = audioArgs,
                expectedDurationSec = expectedAudioDur,
                onProgress = { curSec, pct, spd, kb ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    cpuBooster.pulseIfDue()
                    val liveS = AppLanguageController.currentStrings()
                    val nowMs = SystemClock.elapsedRealtime()
                    val wholeSec = kotlin.math.floor(curSec.coerceAtLeast(0.0)).toLong()
                    val tenthMb = (kb * 10L) / 1024L
                    val pctChanged = pct != lastEmittedPct
                    val visualChanged = pctChanged || wholeSec != lastEmittedWholeSec || tenthMb != lastEmittedTenthMb
                    if (pctChanged || (visualChanged && nowMs - lastUiEmitMs >= 250L)) {
                        lastUiEmitMs = nowMs
                        lastEmittedPct = pct
                        lastEmittedWholeSec = wholeSec
                        lastEmittedTenthMb = tenthMb
                        EncodingJobController.updateStateIfActive(
                            runId,
                            EncodingState.Running(
                                modeTitle = liveS.turboAudioConvertModeTitle,
                                outputFileName = audioPrepFile.name,
                                passIndex = 1,
                                totalPasses = 1,
                                statusMessage = liveS.turboAudioConvertStepStatus(safeAb),
                                currentTimeSec = wholeSec.toDouble(),
                                totalDurationSec = expectedAudioDur,
                                percent = pct,
                                speed = spd,
                                sizeKb = kb,
                                logs = currentLogs(),
                                summaryBanner = liveS.bannerAudioOnly(safeAb),
                                videoSpecsSummary = null,
                                presetLabel = null,
                                isHardwareTurbo = false,
                                canSwitchToTurbo = false,
                                isTurboPreRemux = true
                            )
                        )
                    }
                    updateNotificationThrottled(
                        runId = runId,
                        title = "FFmpeg · ${liveS.turboAudioConvertModeTitle}",
                        text = FfmpegMath.formatTelemetryLine(wholeSec.toDouble(), expectedAudioDur, pct, spd, kb, includePercent = false, strings = liveS),
                        percent = pct,
                        passIndex = 1,
                        totalPasses = 1,
                        force = false
                    )
                },
                onLogLine = { line ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    appendLog(line)
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastLogEmitMs >= 320L) {
                        lastLogEmitMs = nowMs
                        val snapshot = currentLogs()
                        val cur = EncodingJobController.state.value
                        if (cur is EncodingState.Running && !cur.isHardwareTurbo && cur.logs !== snapshot) {
                            EncodingJobController.updateStateIfActive(runId, cur.copy(logs = snapshot))
                        }
                    }
                }
            )
            // Free intermediate lossless pre-cut MKV immediately after audio conversion finishes
            runCatching { preCut?.preCutFile?.delete() }

            if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                return
            }

            if (!stepResult.success || !audioPrepFile.exists() || audioPrepFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                val report = Media3CompatibilityAdvisor.diagnose(media, config, DeviceCodecInventory.queryCurrentDevice())
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.turboVideoAudioPromptBody(true),
                        hint = curS.turboAfterRemuxFastHint,
                        logs = emptyList(),
                        wasCancelled = false,
                        isHardwareTurbo = true,
                        isUnsupportedFormat = true,
                        hasTriedRemux = true,
                        recoveryStep = TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO,
                        diagnosisSummary = formatDiagnosisSummary(report, curS),
                        recommendedAudioBitrateKbps = safeAb,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = true,
                        hasTriedVideoAudioConvert = false
                    )
                )
                return
            }

            var finalTargetConfig = config
            var finalDur = media.durationSec
            if (preCut != null) {
                val finalTiming = FfmpegEngine.probeClipTiming(audioPrepFile)
                finalDur = finalTiming.durationSec ?: preCut.preCutTiming.durationSec ?: media.durationSec
                finalTargetConfig = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
                    originalTargetConfig = config,
                    bracket = preCut.bracket,
                    preparedDurationSec = finalTiming.durationSec,
                    preparedFirstVideoPtsSec = finalTiming.firstVideoPtsSec
                ) ?: preCut.adjustedTargetConfig
            }

            slideBackToTurboAndRun(
                runId = runId,
                startId = startId,
                media = media.copy(
                    extension = "mp4",
                    audioCodec = "aac",
                    audioBitrateKbps = safeAb,
                    durationSec = finalDur
                ),
                config = finalTargetConfig,
                preparedFile = audioPrepFile,
                hasTriedRemux = true,
                hasTriedAudioConvert = true,
                hasTriedVideoAudioConvert = false,
                extraDirToCleanup = audioWorkDir
            )
            handedOffToTurbo = true
        } catch (_: CancellationException) {
            // Cancelled cleanly
        } catch (_: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.turboVideoAudioPromptBody(true),
                        hint = curS.turboAfterRemuxFastHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = true,
                        hasTriedRemux = true,
                        recoveryStep = TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO,
                        recommendedAudioBitrateKbps = safeAb,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = true
                    )
                )
            }
        } finally {
            if (!handedOffToTurbo) {
                runCatching { audioWorkDir.deleteRecursively() }
                teardownIfStillOwner(runId, startId)
            }
        }
    }

    /**
     * Progressive Recovery Step 3/4: Runs the user's configured Video-Only or Video+Audio CRF
     * encode into a temporary MP4 (never polluting Gallery), then slides automatically back to the
     * Turbo speedometer and runs [turboTargetConfig] on the newly compatible file.
     * When [turboTargetConfig] trims a sub-range, silently pre-cuts ONLY the safe keyframe bracket
     * losslessly first so FFmpeg encodes only the short clip instead of the full movie.
     */
    private suspend fun runVideoAudioPrepThenTurboPipeline(
        runId: Long,
        startId: Int,
        media: MediaInfo,
        prepConfig: ModeConfigState,
        turboTargetConfig: ModeConfigState
    ) {
        cpuBooster.start(CpuPerformanceBooster.BoostMode.FFMPEG_SOFTWARE, serviceScope)
        val s = AppLanguageController.currentStrings()
        val isVideoOnlyPrep = prepConfig.mode == ProcessingMode.VIDEO_ONLY_CRF
        fun resolvePrepTitle(strings: AppStrings): String =
            if (isVideoOnlyPrep) strings.turboVideoOnlyPrepModeTitle else strings.turboVideoAudioPrepModeTitle
        val prepWorkDir = FfmpegEngine.createWorkDir(applicationContext)
        val prepFile = File(
            prepWorkDir,
            if (isVideoOnlyPrep) "turbo_video_only_prep.mp4" else "turbo_video_audio_prep.mp4"
        )
        val collectedLogs = mutableListOf<String>()
        var logsSnapshot: List<String> = emptyList()
        val summaryBanner = buildSummaryBanner(media, prepConfig, s)
        val presetLabel = resolvePresetDisplayLabel(prepConfig)
        val targetModeTitle = "${turboTargetConfig.mode.formattedNumber} · ${turboTargetConfig.mode.localizedTitle(s)}"

        fun appendLog(line: String) {
            synchronized(collectedLogs) {
                if (collectedLogs.lastOrNull() == line) return
                if (collectedLogs.size >= 80) collectedLogs.removeAt(0)
                collectedLogs.add(line)
                logsSnapshot = collectedLogs.toList()
            }
        }

        fun currentLogs(): List<String> = synchronized(collectedLogs) { logsSnapshot }

        appendLog("${s.logTagStarted} ${resolvePrepTitle(s)} — $summaryBanner")
        var handedOffToTurbo = false

        try {
            val preCut = executeSilentLosslessTrimPreCut(
                runId = runId,
                media = media,
                targetConfig = turboTargetConfig,
                workDir = prepWorkDir,
                modeTitle = resolvePrepTitle(s),
                summaryBanner = summaryBanner,
                presetLabel = presetLabel,
                appendLog = ::appendLog,
                currentLogs = ::currentLogs
            )
            if (!EncodingJobController.isRunActive(runId)) return

            val effectivePrepMedia = if (preCut != null) {
                media.copy(
                    uri = Uri.fromFile(preCut.preCutFile),
                    extension = "mkv",
                    durationSec = preCut.preCutTiming.durationSec ?: preCut.bracket.expectedSpanSec ?: media.durationSec
                )
            } else {
                media
            }

            val passLogPrefix = File(prepWorkDir, "ff2pass_prep").absolutePath
            val inputPlaceholder = "__FFMPEG_LAZY_PREP_INPUT__"
            val steps = FfmpegMath.buildFfmpegSteps(
                media = effectivePrepMedia,
                config = prepConfig,
                inputTokenProvider = { inputPlaceholder },
                outputPath = prepFile.absolutePath,
                passLogPrefix = passLogPrefix,
                strings = s
            )

            for ((idx, step) in steps.withIndex()) {
                if (!EncodingJobController.isRunActive(runId)) return
                val passNum = idx + 1
                val totalPasses = steps.size
                val stepArgs = step.args.map { arg ->
                    if (arg == inputPlaceholder) {
                        FfmpegEngine.resolveInputPath(applicationContext, effectivePrepMedia.uri)
                    } else {
                        arg
                    }
                }

                var lastUiEmitMs = 0L
                var lastEmittedPct: Int? = null
                var lastEmittedWholeSec = -1L
                var lastEmittedTenthMb = -1L
                var lastLogEmitMs = 0L

                cpuBooster.scheduleEarlyThreadDiscovery(serviceScope)
                val stepResult = FfmpegEngine.executeStep(
                    args = stepArgs,
                    expectedDurationSec = step.expectedDurationSec,
                    onProgress = { curSec, pct, spd, kb ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        cpuBooster.pulseIfDue()
                        val liveS = AppLanguageController.currentStrings()
                        val liveTitle = resolvePrepTitle(liveS)
                        val nowMs = SystemClock.elapsedRealtime()
                        val wholeSec = kotlin.math.floor(curSec.coerceAtLeast(0.0)).toLong()
                        val tenthMb = (kb * 10L) / 1024L
                        val pctChanged = pct != lastEmittedPct
                        val visualChanged = pctChanged || wholeSec != lastEmittedWholeSec || tenthMb != lastEmittedTenthMb
                        if (pctChanged || (visualChanged && nowMs - lastUiEmitMs >= 250L)) {
                            lastUiEmitMs = nowMs
                            lastEmittedPct = pct
                            lastEmittedWholeSec = wholeSec
                            lastEmittedTenthMb = tenthMb
                            EncodingJobController.updateStateIfActive(
                                runId,
                                EncodingState.Running(
                                    modeTitle = liveTitle,
                                    outputFileName = prepFile.name,
                                    passIndex = passNum,
                                    totalPasses = totalPasses,
                                    statusMessage = step.label,
                                    currentTimeSec = wholeSec.toDouble(),
                                    totalDurationSec = step.expectedDurationSec,
                                    percent = pct,
                                    speed = spd,
                                    sizeKb = kb,
                                    logs = currentLogs(),
                                    summaryBanner = summaryBanner,
                                    videoSpecsSummary = null,
                                    presetLabel = presetLabel,
                                    isHardwareTurbo = false,
                                    canSwitchToTurbo = false,
                                    isTurboPreRemux = true
                                )
                            )
                        }
                        updateNotificationThrottled(
                            runId = runId,
                            title = "FFmpeg · $liveTitle",
                            text = FfmpegMath.formatTelemetryLine(wholeSec.toDouble(), step.expectedDurationSec, pct, spd, kb, includePercent = false, strings = liveS),
                            percent = pct,
                            passIndex = passNum,
                            totalPasses = totalPasses,
                            force = false
                        )
                    },
                    onLogLine = { line ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        appendLog(line)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastLogEmitMs >= 320L) {
                            lastLogEmitMs = nowMs
                            val snapshot = currentLogs()
                            val cur = EncodingJobController.state.value
                            if (cur is EncodingState.Running && !cur.isHardwareTurbo && cur.logs !== snapshot) {
                                EncodingJobController.updateStateIfActive(runId, cur.copy(logs = snapshot))
                            }
                        }
                    }
                )

                if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                    return
                }
                if (!stepResult.success) {
                    val curS = AppLanguageController.currentStrings()
                    appendLog("${curS.logTagError} ${curS.errFfmpegExitCode(stepResult.returnCode)}")
                    if (isVideoOnlyPrep && media.hasAudio) {
                        // Video-Only (`-c:a copy`) couldn't mux cleanly into MP4; escalate to Video+Audio
                        EncodingJobController.updateStateIfActive(
                            runId,
                            EncodingState.Failed(
                                modeTitle = targetModeTitle,
                                errorMessage = curS.turboVideoAudioPromptBody(false),
                                hint = curS.turboAfterRemuxFastHint,
                                logs = currentLogs(),
                                isHardwareTurbo = true,
                                isUnsupportedFormat = true,
                                hasTriedRemux = true,
                                sourceAudioBitrateKbps = media.audioBitrateKbps,
                                sourceAudioCodec = media.audioCodec,
                                isLosslessSourceAudio = media.isLosslessAudio,
                                hasSourceAudio = media.hasAudio,
                                hasTriedVideoOnlyConvert = true,
                                recoveryStep = TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO
                            )
                        )
                    } else {
                        EncodingJobController.updateStateIfActive(
                            runId,
                            EncodingState.Failed(
                                modeTitle = resolvePrepTitle(curS),
                                errorMessage = curS.errFfmpegExitCode(stepResult.returnCode),
                                hint = curS.turboAfterRemuxFastHint,
                                logs = currentLogs()
                            )
                        )
                    }
                    return
                }
            }
            // Free intermediate lossless pre-cut MKV immediately after CRF prep finishes
            runCatching { preCut?.preCutFile?.delete() }

            if (!EncodingJobController.isRunActive(runId)) return

            if (!prepFile.exists() || prepFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                val nextStep = if (isVideoOnlyPrep && media.hasAudio) {
                    TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO
                } else {
                    TurboRecoveryStep.EXHAUSTED
                }
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = targetModeTitle,
                        errorMessage = curS.errOutputNotCreated,
                        hint = curS.turboAfterRemuxFastHint,
                        logs = currentLogs(),
                        isHardwareTurbo = true,
                        hasTriedRemux = true,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = !isVideoOnlyPrep,
                        hasTriedVideoOnlyConvert = true,
                        hasTriedVideoAudioConvert = !isVideoOnlyPrep,
                        recoveryStep = nextStep
                    )
                )
                return
            }

            // Probe the freshly encoded compatible file while preserving original baseName/fileName
            // so the final Turbo filename matches the user's original input cleanly.
            val probedPrep = runCatching {
                FfmpegEngine.probeMedia(applicationContext, Uri.fromFile(prepFile))
            }.getOrNull()
            val preparedMedia = if (probedPrep != null) {
                probedPrep.copy(
                    fileName = media.fileName,
                    baseName = media.baseName,
                    extension = "mp4"
                )
            } else {
                media.copy(
                    extension = "mp4",
                    durationSec = effectivePrepMedia.durationSec,
                    videoCodec = if (prepConfig.codec == VideoCodecOption.H265) "hevc" else "h264",
                    pixFmt = prepConfig.bitDepth.pixFmt,
                    audioCodec = when {
                        !media.hasAudio -> null
                        isVideoOnlyPrep -> media.audioCodec
                        else -> "aac"
                    }
                )
            }

            val finalTurboTargetConfig = if (preCut != null) {
                val finalTiming = FfmpegEngine.probeClipTiming(prepFile)
                FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
                    originalTargetConfig = turboTargetConfig,
                    bracket = preCut.bracket,
                    preparedDurationSec = finalTiming.durationSec ?: preparedMedia.durationSec,
                    preparedFirstVideoPtsSec = finalTiming.firstVideoPtsSec
                ) ?: preCut.adjustedTargetConfig
            } else {
                turboTargetConfig
            }

            slideBackToTurboAndRun(
                runId = runId,
                startId = startId,
                media = preparedMedia,
                config = finalTurboTargetConfig,
                preparedFile = prepFile,
                hasTriedRemux = true,
                hasTriedAudioConvert = !isVideoOnlyPrep,
                hasTriedVideoOnlyConvert = true,
                hasTriedVideoAudioConvert = !isVideoOnlyPrep,
                extraDirToCleanup = prepWorkDir
            )
            handedOffToTurbo = true
        } catch (_: CancellationException) {
            // Cancelled cleanly
        } catch (t: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = targetModeTitle,
                        errorMessage = t.message ?: curS.errUnexpected,
                        hint = curS.turboAfterRemuxFastHint,
                        logs = currentLogs(),
                        isHardwareTurbo = true,
                        hasTriedRemux = true,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = !isVideoOnlyPrep,
                        hasTriedVideoOnlyConvert = true,
                        hasTriedVideoAudioConvert = !isVideoOnlyPrep,
                        recoveryStep = if (isVideoOnlyPrep && media.hasAudio) {
                            TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO
                        } else {
                            TurboRecoveryStep.EXHAUSTED
                        }
                    )
                )
            }
        } finally {
            if (!handedOffToTurbo) {
                runCatching { prepWorkDir.deleteRecursively() }
                teardownIfStillOwner(runId, startId)
            }
        }
    }

    /**
     * Common seamless Slide-2 handoff from any FFmpeg preparation step (Remux, Audio-Only,
     * Video-Only, or Video+Audio) straight back onto the Turbo speedometer without prompting again.
     */
    private suspend fun slideBackToTurboAndRun(
        runId: Long,
        startId: Int,
        media: MediaInfo,
        config: ModeConfigState,
        preparedFile: File,
        hasTriedRemux: Boolean,
        hasTriedAudioConvert: Boolean,
        hasTriedVideoAudioConvert: Boolean,
        extraDirToCleanup: File,
        hasTriedVideoOnlyConvert: Boolean = false
    ) {
        cpuBooster.stop()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
        val curS = AppLanguageController.currentStrings()
        val modeTitle = "${config.mode.formattedNumber} · ${config.mode.localizedTitle(curS)}"
        val baseOut = FfmpegMath.computeOutputFileName(media, config, curS)
        val turboOut = Media3TurboEngine.buildTurboOutputFileName(baseOut)
        val turboUnifiedSpecs = buildTurboUnifiedSpecs(media, config, curS)
        val expectedDur = if (config.mode == ProcessingMode.TRIM_VIDEO) {
            FfmpegMath.resolveTrimRange(config, media.durationSec).third ?: media.durationSec
        } else {
            media.durationSec
        }
        EncodingJobController.markTurboEngineActive(true)
        EncodingJobController.updateStateIfActive(
            runId,
            EncodingState.Running(
                modeTitle = modeTitle,
                outputFileName = turboOut,
                passIndex = 1,
                totalPasses = 1,
                statusMessage = curS.turboStatusPreparing,
                currentTimeSec = 0.0,
                totalDurationSec = expectedDur,
                percent = 0,
                speed = "...",
                sizeKb = 0L,
                logs = emptyList(),
                summaryBanner = turboUnifiedSpecs,
                videoSpecsSummary = turboUnifiedSpecs,
                presetLabel = "MediaCodec",
                isHardwareTurbo = true,
                canSwitchToTurbo = false,
                elapsedSec = 0.0
            )
        )
        updateNotificationThrottled(
            runId = runId,
            title = "${curS.turboNotifTitle} · ${config.mode.localizedTitle(curS)}",
            text = curS.turboStatusPreparing,
            percent = 0,
            passIndex = 1,
            totalPasses = 1,
            force = true
        )
        cpuBooster.stop()
        val slideStartMs = SystemClock.elapsedRealtime()
        FfmpegEngine.awaitActiveSessionTermination(timeoutMs = 3_000L)
        val settleRemainMs = (1_000L - (SystemClock.elapsedRealtime() - slideStartMs)).coerceAtLeast(0L)
        if (settleRemainMs > 0L) {
            delay(settleRemainMs)
        }
        if (!EncodingJobController.isRunActive(runId)) return

        runTurboPipeline(
            runId = runId,
            startId = startId,
            media = media,
            config = config,
            inputUriOverride = Uri.fromFile(preparedFile),
            hasTriedRemux = hasTriedRemux,
            hasTriedAudioConvert = hasTriedAudioConvert,
            hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert,
            hasTriedVideoAudioConvert = hasTriedVideoAudioConvert,
            extraDirToCleanup = extraDirToCleanup
        )
    }

    /**
     * Silent, zero-prompt post-encode FFmpeg audio rescue: if Media3 / DirectMediaCodec produced
     * a video track whose audio was dropped or contains zero readable samples, copies the freshly
     * encoded 10-bit/8-bit hardware video stream untouched (`-c:v copy`) and cleanly muxes the
     * matching trimmed/full audio track from the source in ~100–300ms.
     */
    private suspend fun rescueMissingTurboAudio(
        videoOnlyFile: File,
        effectiveSourceUri: Uri,
        media: MediaInfo,
        config: ModeConfigState,
        plan: TurboBitratePlan?,
        workDir: File
    ): File? {
        val wantsAudioCopy = config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
            (config.mode == ProcessingMode.TRIM_VIDEO && config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY)
        val srcAudioCodec = media.audioCodec?.lowercase(Locale.US).orEmpty()
        val canCopyAudio = wantsAudioCopy && srcAudioCodec in Media3CompatibilityAdvisor.MP4_COPY_SAFE_AUDIO_CODECS
        val targetAudioKbps = (plan?.audioBitrateBps?.div(1000))
            ?.takeIf { it in 32..768 }
            ?: Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media)
        val trimRange = if (config.mode == ProcessingMode.TRIM_VIDEO) {
            FfmpegMath.resolveTrimRange(config, media.durationSec)
        } else {
            null
        }

        suspend fun attemptMux(copyAudio: Boolean, tag: String): File? {
            val outCandidate = File(workDir, "turbo_audio_rescued_$tag.mp4")
            runCatching { if (outCandidate.exists()) outCandidate.delete() }
            val inputPath = FfmpegEngine.resolveInputPath(applicationContext, effectiveSourceUri)
            val args = mutableListOf(
                "-y",
                "-i", videoOnlyFile.absolutePath
            )
            if (trimRange != null) {
                if (!trimRange.first.isNullOrBlank()) {
                    args.add("-ss")
                    args.add(trimRange.first!!)
                }
                if (!trimRange.second.isNullOrBlank()) {
                    args.add("-to")
                    args.add(trimRange.second!!)
                }
            }
            args.addAll(
                listOf(
                    "-i", inputPath,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy"
                )
            )
            if (config.codec == VideoCodecOption.H265) {
                args.addAll(listOf("-tag:v", "hvc1"))
            }
            if (copyAudio) {
                args.addAll(listOf("-c:a", "copy"))
            } else {
                args.addAll(listOf("-c:a", "aac", "-b:a", "${targetAudioKbps}k"))
            }
            args.addAll(
                listOf(
                    "-sn", "-dn",
                    "-shortest",
                    "-max_muxing_queue_size", "1024",
                    "-movflags", "+faststart",
                    outCandidate.absolutePath
                )
            )
            val res = FfmpegEngine.executeStep(
                args = args,
                expectedDurationSec = null,
                onProgress = { _, _, _, _ -> },
                onLogLine = {}
            )
            if (res.cancelled || !res.success || !outCandidate.exists() || outCandidate.length() == 0L) {
                runCatching { outCandidate.delete() }
                return null
            }
            val verified = FfmpegEngine.probeEncodedOutput(outCandidate)
            return if (verified.hasVideo && verified.hasReadableAudioSamples) {
                outCandidate
            } else {
                runCatching { outCandidate.delete() }
                null
            }
        }

        if (canCopyAudio) {
            attemptMux(copyAudio = true, tag = "copy")?.let { return it }
        }
        return attemptMux(copyAudio = false, tag = "aac")
    }

    private suspend fun runTurboPipeline(
        runId: Long,
        startId: Int,
        media: MediaInfo,
        config: ModeConfigState,
        inputUriOverride: Uri?,
        hasTriedRemux: Boolean,
        extraDirToCleanup: File?,
        hasTriedAudioConvert: Boolean = false,
        hasTriedVideoOnlyConvert: Boolean = false,
        hasTriedVideoAudioConvert: Boolean = false
    ) {
        EncodingJobController.markTurboEngineActive(true)
        cpuBooster.start(CpuPerformanceBooster.BoostMode.TURBO_MEDIACODEC, serviceScope)
        val s = AppLanguageController.currentStrings()
        val workDir = FfmpegEngine.createWorkDir(applicationContext)
        val modeLocalizedTitle = config.mode.localizedTitle(s)
        val modeTitle = "${config.mode.formattedNumber} · $modeLocalizedTitle"
        val baseOutputName = FfmpegMath.computeOutputFileName(media, config, s)
        val turboOutputName = Media3TurboEngine.buildTurboOutputFileName(baseOutputName)
        var liveTurboSpecs = buildTurboUnifiedSpecs(media, config, s)
        var resolvedDurationSec = if (config.mode == ProcessingMode.TRIM_VIDEO) {
            FfmpegMath.resolveTrimRange(config, media.durationSec).third ?: media.durationSec ?: 0.0
        } else {
            media.durationSec ?: 0.0
        }
        var lastKnownSpeed = "..."
        var resolvedPlan: TurboBitratePlan? = null

        val compatReport = Media3CompatibilityAdvisor.diagnose(
            media = media,
            config = config,
            device = DeviceCodecInventory.queryCurrentDevice()
        )
        val initialDiagSummary = formatDiagnosisSummary(compatReport, s)

        try {
            // Upfront embedded Media3 1.11.1 + device MediaCodec compatibility diagnosis:
            // Immediately pinpoints container, audio, or video incompatibilities using already-probed
            // metadata and routes straight to the exact matching recovery step.
            if (!hasTriedRemux &&
                !hasTriedAudioConvert &&
                !hasTriedVideoOnlyConvert &&
                !hasTriedVideoAudioConvert &&
                compatReport.requiresImmediateRecovery
            ) {
                val immediateStep = Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                    report = compatReport,
                    hasAudio = media.hasAudio,
                    errorCodeName = null,
                    errorDetail = null,
                    hasTriedRemux = false,
                    hasTriedAudioConvert = false,
                    hasTriedVideoAudioConvert = false,
                    hasTriedVideoOnlyConvert = false
                )
                val immediateBody = when (immediateStep) {
                    TurboRecoveryStep.OFFER_AUDIO_CONVERT -> s.turboAudioConvertPromptBody(false)
                    TurboRecoveryStep.OFFER_VIDEO_ONLY -> s.turboVideoOnlyPromptBody(media.hasAudio)
                    TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO -> s.turboVideoAudioPromptBody(false)
                    else -> s.turboUnsupportedBody
                }
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = immediateBody,
                        hint = s.turboUnsupportedHint,
                        logs = emptyList(),
                        wasCancelled = false,
                        isHardwareTurbo = true,
                        isUnsupportedFormat = true,
                        hasTriedRemux = false,
                        recoveryStep = immediateStep,
                        diagnosisSummary = initialDiagSummary,
                        recommendedAudioBitrateKbps = compatReport.recommendedAudioBitrateKbps,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = false,
                        hasTriedVideoOnlyConvert = false,
                        hasTriedVideoAudioConvert = false
                    )
                )
                return
            }

            val tempOutFile = File(workDir, turboOutputName)
            var lastKnownPercent = 0
            var cachedTurboSizeKb = 0L
            var lastSizeSampleMs = 0L
            val sizeSamplerActive = AtomicBoolean(false)
            var lastSpeedCalcMs = 0L
            var lastSpeedPct = -1
            var lastEmittedPct = -1
            var lastEmittedStatus = ""
            var lastEmittedSpeed = ""
            var lastEmittedTenthMb = -1L
            var lastEmittedWholeSec = -1L
            var lastUiEmitMs = 0L

            val result = Media3TurboEngine.transcode(
                context = applicationContext,
                media = media,
                config = config,
                outputFile = tempOutFile,
                inputUriOverride = inputUriOverride,
                onPlanResolved = { plan ->
                    resolvedPlan = plan
                    val liveS = AppLanguageController.currentStrings()
                    resolvedDurationSec = plan.effectiveDurationSec
                    liveTurboSpecs = buildTurboUnifiedSpecs(
                        media = media,
                        config = config,
                        s = liveS,
                        overrideVideoBitrateBps = plan.videoBitrateBps,
                        displayDimsOverride = plan.resolvedDisplayDims
                    )
                    val cur = EncodingJobController.state.value
                    if (cur is EncodingState.Running && cur.isHardwareTurbo) {
                        EncodingJobController.updateStateIfActive(
                            runId,
                            cur.copy(
                                totalDurationSec = resolvedDurationSec.takeIf { it > 0.0 } ?: cur.totalDurationSec,
                                summaryBanner = liveTurboSpecs,
                                videoSpecsSummary = liveTurboSpecs
                            )
                        )
                    }
                },
                onProgress = { pct, elapsedSec, effectiveDur ->
                    if (!EncodingJobController.isRunActive(runId)) return@transcode
                    val liveS = AppLanguageController.currentStrings()
                    val nowMs = SystemClock.elapsedRealtime()
                    if (effectiveDur > 0.0) {
                        resolvedDurationSec = effectiveDur
                    }
                    // Reset progress & speed cleanly when Turbo hands off to a fallback tier
                    if (pct < 0 && elapsedSec == 0.0) {
                        lastKnownPercent = 0
                        lastKnownSpeed = "..."
                        cachedTurboSizeKb = 0L
                        lastSpeedCalcMs = 0L
                        lastSpeedPct = -1
                    } else if (pct >= 0) {
                        val clampedPct = pct.coerceIn(0, 100)
                        if (clampedPct >= lastKnownPercent) {
                            lastKnownPercent = clampedPct
                        }
                    }
                    val statusMsg = if (pct < 0 || lastKnownPercent == 0) {
                        liveS.turboStatusPreparing
                    } else {
                        liveS.turboStatusEncoding
                    }
                    val totalDur = resolvedDurationSec.takeIf { it > 0.0 }
                    val processedSec = if (totalDur != null) {
                        (totalDur * (lastKnownPercent / 100.0)).coerceIn(0.0, totalDur)
                    } else {
                        kotlin.math.floor(elapsedSec.coerceAtLeast(0.0))
                    }
                    val wholeSec = kotlin.math.floor(processedSec.coerceAtLeast(0.0)).toLong()
                    if (lastKnownPercent != lastSpeedPct || nowMs - lastSpeedCalcMs >= 1_000L || pct == 100) {
                        lastSpeedPct = lastKnownPercent
                        lastSpeedCalcMs = nowMs
                        lastKnownSpeed = if (totalDur != null && elapsedSec >= 0.4 && processedSec >= 0.05) {
                            String.format(Locale.US, "%.2fx", processedSec / elapsedSec)
                        } else {
                            "..."
                        }
                    }
                    val speedStr = lastKnownSpeed

                    // Sample output size asynchronously on Dispatchers.IO (never touch filesystem on Main Thread)
                    if (nowMs - lastSizeSampleMs >= 500L && sizeSamplerActive.compareAndSet(false, true)) {
                        lastSizeSampleMs = nowMs
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                cachedTurboSizeKb = if (tempOutFile.exists()) tempOutFile.length() / 1024L else 0L
                            } catch (_: Throwable) {
                                // Best-effort live telemetry size
                            } finally {
                                sizeSamplerActive.set(false)
                            }
                        }
                    }
                    val liveSizeKb = cachedTurboSizeKb
                    val tenthMb = (liveSizeKb * 10L) / 1024L

                    val mustEmit = lastKnownPercent != lastEmittedPct ||
                        statusMsg != lastEmittedStatus ||
                        ((speedStr != lastEmittedSpeed || tenthMb != lastEmittedTenthMb || wholeSec != lastEmittedWholeSec) &&
                            nowMs - lastUiEmitMs >= 240L)

                    if (mustEmit) {
                        lastUiEmitMs = nowMs
                        lastEmittedPct = lastKnownPercent
                        lastEmittedStatus = statusMsg
                        lastEmittedSpeed = speedStr
                        lastEmittedTenthMb = tenthMb
                        lastEmittedWholeSec = wholeSec
                        EncodingJobController.updateStateIfActive(
                            runId,
                            EncodingState.Running(
                                modeTitle = modeTitle,
                                outputFileName = turboOutputName,
                                passIndex = 1,
                                totalPasses = 1,
                                statusMessage = statusMsg,
                                currentTimeSec = wholeSec.toDouble(),
                                totalDurationSec = totalDur,
                                percent = lastKnownPercent,
                                speed = speedStr,
                                sizeKb = liveSizeKb,
                                logs = emptyList(),
                                summaryBanner = liveTurboSpecs,
                                videoSpecsSummary = liveTurboSpecs,
                                presetLabel = "MediaCodec",
                                isHardwareTurbo = true,
                                canSwitchToTurbo = false,
                                elapsedSec = 0.0
                            )
                        )
                    }
                    val notifTelemetry = FfmpegMath.formatTelemetryLine(
                        currentSec = wholeSec.toDouble(),
                        totalDurationSec = totalDur,
                        percent = lastKnownPercent,
                        speed = speedStr,
                        sizeKb = liveSizeKb,
                        includePercent = false,
                        strings = liveS
                    )
                    updateNotificationThrottled(
                        runId = runId,
                        title = "${liveS.turboNotifTitle} · ${config.mode.localizedTitle(liveS)}",
                        text = notifTelemetry,
                        percent = lastKnownPercent,
                        passIndex = 1,
                        totalPasses = 1,
                        force = false
                    )
                }
            )

            if (!EncodingJobController.isRunActive(runId) || result.cancelled) {
                return
            }

            if (!result.success) {
                val curS = AppLanguageController.currentStrings()
                val curDiag = formatDiagnosisSummary(compatReport, curS)
                val isTenBitBlocked = result.errorCodeName == "ERROR_CODE_10BIT_NOT_PRESERVED"
                val nextStep = Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                    report = compatReport,
                    hasAudio = media.hasAudio,
                    errorCodeName = result.errorCodeName,
                    errorDetail = result.errorDetail,
                    hasTriedRemux = hasTriedRemux,
                    hasTriedAudioConvert = hasTriedAudioConvert,
                    hasTriedVideoAudioConvert = hasTriedVideoAudioConvert,
                    hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert
                )
                val detailSuffix = listOfNotNull(result.errorCodeName, result.errorDetail)
                    .filter { it.isNotBlank() }
                    .joinToString(" — ")
                val errorMessage = when {
                    isTenBitBlocked -> curS.turbo10BitNotPreservedBody
                    hasTriedVideoAudioConvert -> {
                        if (detailSuffix.isNotBlank()) "${curS.turboAfterVideoAudioFailedBody}\n($detailSuffix)"
                        else curS.turboAfterVideoAudioFailedBody
                    }
                    hasTriedVideoOnlyConvert || hasTriedAudioConvert -> {
                        if (detailSuffix.isNotBlank()) "${curS.turboVideoAudioPromptBody(hasTriedAudioConvert)}\n($detailSuffix)"
                        else curS.turboVideoAudioPromptBody(hasTriedAudioConvert)
                    }
                    hasTriedRemux -> {
                        if (detailSuffix.isNotBlank()) "${curS.turboAfterRemuxFailedBody}\n($detailSuffix)"
                        else curS.turboAfterRemuxFailedBody
                    }
                    result.isUnsupportedFormat -> {
                        if (detailSuffix.isNotBlank()) "${curS.turboUnsupportedBody}\n($detailSuffix)"
                        else curS.turboUnsupportedBody
                    }
                    else -> detailSuffix.ifBlank { curS.errUnexpected }
                }
                val hint = when {
                    isTenBitBlocked -> curS.turbo10BitNotPreservedHint
                    hasTriedRemux || hasTriedAudioConvert || hasTriedVideoOnlyConvert || hasTriedVideoAudioConvert -> curS.turboAfterRemuxFastHint
                    result.isUnsupportedFormat -> curS.turboUnsupportedHint
                    else -> curS.turboErrorGenericHint
                }
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = errorMessage,
                        hint = hint,
                        logs = emptyList(),
                        wasCancelled = false,
                        isHardwareTurbo = true,
                        isUnsupportedFormat = !isTenBitBlocked && (result.isUnsupportedFormat || hasTriedRemux || nextStep != TurboRecoveryStep.EXHAUSTED),
                        hasTriedRemux = hasTriedRemux,
                        recoveryStep = nextStep,
                        diagnosisSummary = if (isTenBitBlocked) curS.turbo10BitNotPreservedTitle else curDiag,
                        recommendedAudioBitrateKbps = compatReport.recommendedAudioBitrateKbps,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = hasTriedAudioConvert,
                        hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert,
                        hasTriedVideoAudioConvert = hasTriedVideoAudioConvert
                    )
                )
                return
            }

            if (!tempOutFile.exists() || tempOutFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                val nextStep = Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                    report = compatReport,
                    hasAudio = media.hasAudio,
                    errorCodeName = "EMPTY_OUTPUT",
                    errorDetail = null,
                    hasTriedRemux = hasTriedRemux,
                    hasTriedAudioConvert = hasTriedAudioConvert,
                    hasTriedVideoAudioConvert = hasTriedVideoAudioConvert,
                    hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert
                )
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = if (hasTriedRemux) curS.turboAfterRemuxFailedBody else curS.errOutputNotCreated,
                        hint = if (hasTriedRemux) curS.turboAfterRemuxFastHint else curS.turboErrorGenericHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = hasTriedRemux || nextStep != TurboRecoveryStep.EXHAUSTED,
                        hasTriedRemux = hasTriedRemux,
                        recoveryStep = nextStep,
                        diagnosisSummary = formatDiagnosisSummary(compatReport, curS),
                        recommendedAudioBitrateKbps = compatReport.recommendedAudioBitrateKbps,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = hasTriedAudioConvert,
                        hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert,
                        hasTriedVideoAudioConvert = hasTriedVideoAudioConvert
                    )
                )
                return
            }

            // Automatic invisible audio integrity verification & FFmpeg rescue:
            // Never silently save a mute video when the source contains audio!
            var finalOutputFile = tempOutFile
            if (media.hasAudio) {
                val outputProbe = FfmpegEngine.probeEncodedOutput(tempOutFile)
                if (!outputProbe.hasReadableAudioSamples) {
                    val liveS = AppLanguageController.currentStrings()
                    val curRunning = EncodingJobController.state.value
                    if (curRunning is EncodingState.Running && curRunning.isHardwareTurbo) {
                        EncodingJobController.updateStateIfActive(
                            runId,
                            curRunning.copy(
                                statusMessage = liveS.turboStatusRestoringAudio,
                                percent = 99
                            )
                        )
                    }
                    val rescued = rescueMissingTurboAudio(
                        videoOnlyFile = tempOutFile,
                        effectiveSourceUri = inputUriOverride ?: media.uri,
                        media = media,
                        config = config,
                        plan = resolvedPlan,
                        workDir = workDir
                    )
                    if (!EncodingJobController.isRunActive(runId)) return
                    if (rescued != null && rescued.exists() && rescued.length() > 0L) {
                        finalOutputFile = rescued
                    } else {
                        val failS = AppLanguageController.currentStrings()
                        val nextStep = if (!hasTriedAudioConvert) {
                            TurboRecoveryStep.OFFER_AUDIO_CONVERT
                        } else if (!hasTriedVideoAudioConvert) {
                            TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO
                        } else {
                            TurboRecoveryStep.EXHAUSTED
                        }
                        EncodingJobController.updateStateIfActive(
                            runId,
                            EncodingState.Failed(
                                modeTitle = modeTitle,
                                errorMessage = failS.turboAudioLostBody,
                                hint = failS.turboUnsupportedHint,
                                logs = emptyList(),
                                wasCancelled = false,
                                isHardwareTurbo = true,
                                isUnsupportedFormat = true,
                                hasTriedRemux = hasTriedRemux,
                                recoveryStep = nextStep,
                                diagnosisSummary = failS.turboDiagAudioIncompatible(media.audioCodec?.uppercase(Locale.US) ?: "AUDIO"),
                                recommendedAudioBitrateKbps = compatReport.recommendedAudioBitrateKbps,
                                sourceAudioBitrateKbps = media.audioBitrateKbps,
                                sourceAudioCodec = media.audioCodec,
                                isLosslessSourceAudio = media.isLosslessAudio,
                                hasSourceAudio = media.hasAudio,
                                hasTriedAudioConvert = hasTriedAudioConvert,
                                hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert,
                                hasTriedVideoAudioConvert = hasTriedVideoAudioConvert
                            )
                        )
                        return
                    }
                }
            }

            val curS = AppLanguageController.currentStrings()
            val finalDur = resolvedDurationSec.takeIf { it > 0.0 }
            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Running(
                    modeTitle = modeTitle,
                    outputFileName = turboOutputName,
                    passIndex = 1,
                    totalPasses = 1,
                    statusMessage = curS.turboStatusSaving,
                    currentTimeSec = finalDur ?: result.elapsedSec,
                    totalDurationSec = finalDur,
                    percent = 100,
                    speed = lastKnownSpeed,
                    sizeKb = finalOutputFile.length() / 1024L,
                    logs = emptyList(),
                    summaryBanner = liveTurboSpecs,
                    videoSpecsSummary = liveTurboSpecs,
                    presetLabel = "MediaCodec",
                    isHardwareTurbo = true,
                    canSwitchToTurbo = false,
                    elapsedSec = result.elapsedSec
                )
            )

            val saved = MediaStoreSaver.saveToPublicStorage(
                context = applicationContext,
                sourceFile = finalOutputFile,
                desiredFileName = turboOutputName
            )
            val completed = CompletedOutput(
                id = UUID.randomUUID().toString(),
                fileName = turboOutputName,
                displayPath = saved.displayPath,
                contentUriString = saved.uri.toString(),
                mimeType = saved.mimeType,
                outputSizeBytes = saved.sizeBytes,
                inputSizeBytes = media.sizeBytes,
                modeLabel = "${config.mode.localizedTitle(curS)} (Turbo)",
                timestampMs = System.currentTimeMillis()
            )
            runCatching { PreferencesRepository(applicationContext).appendHistory(listOf(completed)) }

            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Success(
                    outputs = listOf(completed),
                    modeTitle = modeTitle,
                    summaryMessage = curS.successOutputSize(FfmpegMath.formatSizeBytes(saved.sizeBytes)),
                    logs = emptyList(),
                    isHardwareTurbo = true,
                    elapsedSec = result.elapsedSec
                )
            )
        } catch (_: CancellationException) {
            // Cancelled cleanly
        } catch (t: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                val nextStep = Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                    report = compatReport,
                    hasAudio = media.hasAudio,
                    errorCodeName = t.javaClass.simpleName,
                    errorDetail = t.message,
                    hasTriedRemux = hasTriedRemux,
                    hasTriedAudioConvert = hasTriedAudioConvert,
                    hasTriedVideoAudioConvert = hasTriedVideoAudioConvert,
                    hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert
                )
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = if (hasTriedRemux) curS.turboAfterRemuxFailedBody else (t.message ?: curS.errUnexpected),
                        hint = if (hasTriedRemux) curS.turboAfterRemuxFastHint else curS.turboErrorGenericHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = hasTriedRemux || nextStep != TurboRecoveryStep.EXHAUSTED,
                        hasTriedRemux = hasTriedRemux,
                        recoveryStep = nextStep,
                        diagnosisSummary = formatDiagnosisSummary(compatReport, curS),
                        recommendedAudioBitrateKbps = compatReport.recommendedAudioBitrateKbps,
                        sourceAudioBitrateKbps = media.audioBitrateKbps,
                        sourceAudioCodec = media.audioCodec,
                        isLosslessSourceAudio = media.isLosslessAudio,
                        hasSourceAudio = media.hasAudio,
                        hasTriedAudioConvert = hasTriedAudioConvert,
                        hasTriedVideoOnlyConvert = hasTriedVideoOnlyConvert,
                        hasTriedVideoAudioConvert = hasTriedVideoAudioConvert
                    )
                )
            }
        } finally {
            EncodingJobController.clearTurboEngineIfOwner(runId)
            runCatching { workDir.deleteRecursively() }
            runCatching { extraDirToCleanup?.deleteRecursively() }
            teardownIfStillOwner(runId, startId)
        }
    }

    private suspend fun runCustomCommandMode(
        runId: Long,
        media: MediaInfo,
        config: ModeConfigState,
        workDir: File,
        modeTitle: String,
        summaryBanner: String,
        s: AppStrings,
        currentLogs: () -> List<String>,
        appendLog: (String) -> Unit
    ) {
        val inputPlaceholder = "__FFMPEG_CUSTOM_LAZY_INPUT__"
        val plan = CustomFfmpegParser.buildPlan(
            rawCommand = config.customCommandInput,
            media = media,
            workDirPath = workDir.absolutePath,
            defaultOutputFileName = s.customCmdDefaultOutput,
            inputTokenProvider = { inputPlaceholder },
            isPreview = false
        )
        if (plan.steps.isEmpty()) {
            appendLog("${s.logTagError} ${s.errCustomCmdEmpty}")
            val snapLogs = currentLogs()
            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Failed(
                    modeTitle = modeTitle,
                    errorMessage = s.errCustomCmdEmpty,
                    hint = s.hintCustomCmdExample,
                    logs = snapLogs,
                    rawErrorDetails = FfmpegMath.buildAiRawErrorReport(
                        modeTitle = modeTitle,
                        errorMessage = s.errCustomCmdEmpty,
                        mediaFileName = media.fileName,
                        mediaSummary = describeMediaForErrorReport(media),
                        executedCommand = config.customCommandInput.trim().ifBlank { null },
                        logs = snapLogs
                    )
                )
            )
            return
        }

        val totalSteps = plan.steps.size
        val displayOutName = plan.primaryFinalOutputName
            ?: if (plan.isProbeOnly) s.customCmdProbeOutputPlaceholder else s.customCmdOutputLabel
        val collectedCommandOutputs = mutableListOf<String>()

        for ((idx, step) in plan.steps.withIndex()) {
            if (!EncodingJobController.isRunActive(runId)) return
            val isProbeStep = step.tool == CustomCommandTool.FFPROBE
            val resolvedArgs = step.args.map { arg ->
                if (arg == inputPlaceholder) {
                    FfmpegEngine.resolveInputPath(applicationContext, media.uri)
                } else {
                    arg
                }
            }
            val passNum = idx + 1
            val label = when {
                isProbeStep && totalSteps > 1 -> s.customCmdStepProbeMulti(passNum, totalSteps)
                isProbeStep -> s.customCmdStepProbeSingle
                totalSteps > 1 -> s.customCmdStepMulti(passNum, totalSteps)
                else -> s.customCmdStepSingle
            }
            val stepExpectedDur = if (isProbeStep) null else (step.expectedDurationSec ?: media.durationSec)
            if (totalSteps > 1) {
                appendLog("${s.logTagStep(passNum, totalSteps)} $label")
            }

            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Running(
                    modeTitle = modeTitle,
                    outputFileName = displayOutName,
                    passIndex = passNum,
                    totalPasses = totalSteps,
                    statusMessage = label,
                    currentTimeSec = 0.0,
                    totalDurationSec = stepExpectedDur,
                    percent = if (stepExpectedDur != null) 0 else null,
                    speed = "...",
                    sizeKb = 0L,
                    logs = currentLogs(),
                    summaryBanner = summaryBanner
                )
            )
            updateNotificationThrottled(
                runId = runId,
                title = if (totalSteps > 1) "FFmpeg · ($passNum/$totalSteps) ${s.customCmdOutputLabel}" else "FFmpeg · ${s.customCmdOutputLabel}",
                text = label,
                percent = if (stepExpectedDur != null) 0 else null,
                passIndex = passNum,
                totalPasses = totalSteps,
                force = true
            )

            var lastUiEmitMs = 0L
            var lastEmittedPct: Int? = null
            var lastEmittedWholeSec = -1L
            var lastEmittedTenthMb = -1L
            var lastLogEmitMs = 0L

            val res = if (isProbeStep) {
                FfmpegEngine.executeProbeStep(
                    args = resolvedArgs,
                    onLogLine = { line ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeProbeStep
                        appendLog(line)
                    }
                )
            } else {
                cpuBooster.scheduleEarlyThreadDiscovery(serviceScope)
                FfmpegEngine.executeStep(
                    args = resolvedArgs,
                    expectedDurationSec = stepExpectedDur,
                    captureRawOutput = step.isNullOutputOnly && step.passNumber == null,
                    onProgress = { curSec, pct, spd, kb ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        cpuBooster.pulseIfDue()
                        val liveS = AppLanguageController.currentStrings()
                        val nowMs = SystemClock.elapsedRealtime()
                        val wholeSec = kotlin.math.floor(curSec.coerceAtLeast(0.0)).toLong()
                        val tenthMb = (kb * 10L) / 1024L
                        val pctChanged = pct != lastEmittedPct
                        val visualChanged = pctChanged || wholeSec != lastEmittedWholeSec || tenthMb != lastEmittedTenthMb
                        if (pctChanged || (visualChanged && nowMs - lastUiEmitMs >= 250L)) {
                            lastUiEmitMs = nowMs
                            lastEmittedPct = pct
                            lastEmittedWholeSec = wholeSec
                            lastEmittedTenthMb = tenthMb
                            EncodingJobController.updateStateIfActive(
                                runId,
                                EncodingState.Running(
                                    modeTitle = modeTitle,
                                    outputFileName = displayOutName,
                                    passIndex = passNum,
                                    totalPasses = totalSteps,
                                    statusMessage = label,
                                    currentTimeSec = wholeSec.toDouble(),
                                    totalDurationSec = stepExpectedDur,
                                    percent = pct,
                                    speed = spd,
                                    sizeKb = kb,
                                    logs = currentLogs(),
                                    summaryBanner = summaryBanner
                                )
                            )
                        }
                        val titlePrefix = if (totalSteps > 1) "($passNum/$totalSteps) " else ""
                        updateNotificationThrottled(
                            runId = runId,
                            title = "FFmpeg · $titlePrefix${liveS.customCmdOutputLabel}",
                            text = FfmpegMath.formatTelemetryLine(wholeSec.toDouble(), stepExpectedDur, pct, spd, kb, includePercent = false, strings = liveS),
                            percent = pct,
                            passIndex = passNum,
                            totalPasses = totalSteps,
                            force = false
                        )
                    },
                    onLogLine = { line ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        appendLog(line)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastLogEmitMs >= 320L) {
                            lastLogEmitMs = nowMs
                            val snapshot = currentLogs()
                            val cur = EncodingJobController.state.value
                            if (cur is EncodingState.Running && cur.logs !== snapshot) {
                                EncodingJobController.updateStateIfActive(
                                    runId,
                                    cur.copy(logs = snapshot)
                                )
                            }
                        }
                    }
                )
            }

            if (!EncodingJobController.isRunActive(runId) || res.cancelled) {
                return
            }
            if (!res.success) {
                val curS = AppLanguageController.currentStrings()
                val errMsg = curS.errCustomCmdFailed(res.returnCode)
                appendLog("${curS.logTagError} $errMsg")
                val snapLogs = currentLogs()
                val toolBin = if (isProbeStep) "ffprobe" else "ffmpeg"
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = errMsg,
                        hint = curS.hintCustomCmdSyntax(media.extension),
                        logs = snapLogs,
                        rawErrorDetails = FfmpegMath.buildAiRawErrorReport(
                            modeTitle = modeTitle,
                            errorMessage = errMsg,
                            mediaFileName = media.fileName,
                            mediaSummary = describeMediaForErrorReport(media),
                            executedCommand = FfmpegMath.formatCommandForErrorReport(
                                toolBinary = toolBin,
                                args = resolvedArgs,
                                mediaExtension = media.extension
                            ),
                            returnCode = res.returnCode,
                            rawOutput = res.rawOutput,
                            logs = snapLogs
                        )
                    )
                )
                return
            }

            if ((isProbeStep || (step.isNullOutputOnly && step.passNumber == null)) && res.rawOutput.isNotBlank()) {
                collectedCommandOutputs.add(res.rawOutput.trim())
            }
        }

        if (!EncodingJobController.isRunActive(runId)) return

        val curS = AppLanguageController.currentStrings()
        // Clean 2-pass log artifacts (x264/x265/vpx) and skip consumed intermediate files (e.g. palette.png)
        // whenever final output files were produced.
        val candidateFiles = workDir.listFiles().orEmpty().filter { file ->
            if (!file.isFile) return@filter false
            val n = file.name.lowercase(java.util.Locale.US)
            val isTempPassArtifact = n.endsWith(".log") ||
                n.endsWith(".log.temp") ||
                n.endsWith(".log.mbtree") ||
                n.endsWith(".log.mbtree.temp") ||
                n.endsWith(".log.cutree") ||
                n.endsWith(".log.cutree.temp") ||
                n.endsWith(".cutree") ||
                n.endsWith(".cutree.temp") ||
                n.endsWith(".mbtree") ||
                n.endsWith(".mbtree.temp") ||
                n.startsWith("ffmpeg2pass") ||
                n.startsWith("x265_2pass") ||
                n == "nul"
            if (isTempPassArtifact) {
                file.delete()
                false
            } else {
                file.length() > 0L
            }
        }

        val finalOutputFiles = candidateFiles.filter { it.name !in plan.consumedIntermediateFiles }
            .ifEmpty { candidateFiles }
            .sortedBy { it.name }

        val savedOutputs = mutableListOf<CompletedOutput>()
        finalOutputFiles.forEach { file ->
            val saved = MediaStoreSaver.saveToPublicStorage(applicationContext, file, file.name)
            savedOutputs.add(
                CompletedOutput(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    displayPath = saved.displayPath,
                    contentUriString = saved.uri.toString(),
                    mimeType = saved.mimeType,
                    outputSizeBytes = saved.sizeBytes,
                    inputSizeBytes = media.sizeBytes,
                    modeLabel = config.mode.localizedTitle(curS),
                    timestampMs = System.currentTimeMillis()
                )
            )
        }

        if (savedOutputs.isNotEmpty()) {
            runCatching { PreferencesRepository(applicationContext).appendHistory(savedOutputs) }
        }

        val commandOutputText = collectedCommandOutputs.joinToString("\n\n").ifBlank { null }
        val summary = when {
            savedOutputs.isNotEmpty() -> {
                val totalBytes = savedOutputs.sumOf { it.outputSizeBytes }
                curS.successCustomCmdSavedFiles(savedOutputs.size, FfmpegMath.formatSizeBytes(totalBytes))
            }
            commandOutputText != null -> curS.successCustomCmdOutputReady
            else -> curS.successCustomCmdDoneNoFiles
        }

        appendLog("${curS.logTagCompleted} $summary")
        EncodingJobController.updateStateIfActive(
            runId,
            EncodingState.Success(
                outputs = savedOutputs,
                modeTitle = modeTitle,
                summaryMessage = summary,
                logs = currentLogs(),
                commandOutput = commandOutputText
            )
        )
    }

    private fun describeMediaForErrorReport(media: MediaInfo): String = buildList {
        media.videoCodec?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (media.width != null && media.height != null) add("${media.width}x${media.height}")
        media.pixFmt?.takeIf { it.isNotBlank() }?.let { add(it) }
        media.videoProfile?.takeIf { it.isNotBlank() }?.let { add(it) }
        media.audioCodec?.takeIf { it.isNotBlank() }?.let { ac ->
            val br = media.audioBitrateKbps?.let { " ${it}k" }.orEmpty()
            add("$ac$br")
        }
        media.durationSec?.let { add(String.format(java.util.Locale.US, "%.1fs", it)) }
    }.joinToString(", ")

    /**
     * Silently snaps lossless trim start -> previous keyframe (adaptive `computeSafeNoAccurateSeekSec`
     * buffer, 5ms floor) and end -> next keyframe when the user launches Trim without opening the
     * keyframe picker, guaranteeing the requested range is never clipped short.
     */
    private suspend fun resolveAutoTrimKeyframesIfNeeded(
        media: MediaInfo,
        config: ModeConfigState
    ): ModeConfigState {
        if (config.mode != ProcessingMode.TRIM_VIDEO ||
            config.trimCutStrategy != TrimCutStrategy.LOSSLESS_KEYFRAME ||
            config.trimTimeChoice != TrimTimeChoice.SNAP_KEYFRAMES ||
            !media.hasVideo
        ) {
            return config
        }
        val startRaw = config.startTimeInput.trim().replace(" ", "").replace(',', '.')
        val endRaw = config.endTimeInput.trim().replace(" ", "").replace(',', '.')
        val startSec = if (startRaw.isNotEmpty() && config.selectedStartKfSec == null && config.trimScan.startScan == null) {
            FfmpegMath.parseTimestampToSeconds(startRaw)
        } else null
        val endSec = if (endRaw.isNotEmpty() && config.selectedEndKfSec == null && config.trimScan.endScan == null) {
            FfmpegMath.parseTimestampToSeconds(endRaw)
        } else null

        if (startSec == null && endSec == null) return config

        return try {
            coroutineScope {
                val startDeferred = startSec?.let { st ->
                    async { FfmpegEngine.scanKeyframes(applicationContext, media.uri, st, isStart = true, durationSec = media.durationSec) }
                }
                val endDeferred = endSec?.let { en ->
                    async { FfmpegEngine.scanKeyframes(applicationContext, media.uri, en, isStart = false, durationSec = media.durationSec) }
                }
                FfmpegMath.applyAutoScannedKeyframes(
                    config = config,
                    mediaDurationSec = media.durationSec,
                    startScan = startDeferred?.await(),
                    endScan = endDeferred?.await()
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            config
        }
    }

    private fun resolveContextualHint(config: ModeConfigState, s: AppStrings): String? {
        return when {
            config.mode == ProcessingMode.TRIM_VIDEO && config.trimSubMode == TrimSubMode.FULL_LOSSLESS ->
                s.hintTrimLosslessAudio
            config.mode == ProcessingMode.VIDEO_ONLY_CRF ->
                s.hintVideoOnlyAudio
            config.mode == ProcessingMode.REMUX_FORMAT ->
                s.hintRemuxCompat
            else -> null
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        lastNotification = notification
        lastNotifyElapsedMs.set(SystemClock.elapsedRealtime())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val typeCandidates = BackgroundExecutionPolicy.resolveForegroundServiceTypes(Build.VERSION.SDK_INT)
            for (fgsType in typeCandidates) {
                val started = runCatching {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                    true
                }.getOrDefault(false)
                if (started) return
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationThrottled(
        runId: Long,
        title: String,
        text: String,
        percent: Int?,
        passIndex: Int,
        totalPasses: Int,
        force: Boolean
    ) {
        if (!EncodingJobController.isRunActive(runId)) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastNotifyElapsedMs.get()
        if (!force && now - prev < 500L) return
        if (!force && !lastNotifyElapsedMs.compareAndSet(prev, now)) return
        if (force) {
            lastNotifyElapsedMs.set(now)
        }
        val notif = buildNotification(title, text, percent, passIndex, totalPasses)
        lastNotification = notif
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun refreshNotificationIfSilent() {
        val activeRun = EncodingJobController.currentActiveRunId()
        if (activeRun <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastNotifyElapsedMs.get()
        if (!BackgroundExecutionPolicy.shouldRefreshNotification(prev, now)) return
        val notif = lastNotification ?: return
        if (!lastNotifyElapsedMs.compareAndSet(prev, now)) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notif)
        }
    }

    private fun passSmallIconRes(passIndex: Int, totalPasses: Int): Int = when {
        totalPasses <= 1 -> R.drawable.ic_stat_pass_1_1
        totalPasses == 2 && passIndex <= 1 -> R.drawable.ic_stat_pass_1_2
        totalPasses == 2 -> R.drawable.ic_stat_pass_2_2
        totalPasses == 3 && passIndex <= 1 -> R.drawable.ic_stat_pass_1_3
        totalPasses == 3 && passIndex == 2 -> R.drawable.ic_stat_pass_2_3
        totalPasses == 3 -> R.drawable.ic_stat_pass_3_3
        else -> R.drawable.ic_stat_ffmpeg
    }

    /**
     * Builds an Android 16 Live Update / Promoted Ongoing notification for Honor Magic Capsule:
     * - Left slot (smallIcon): crisp static monochrome "1/2", "2/2", "1/1", etc.
     * - Right slot (shortCriticalText): crisp static "%25" (<= 4 chars so MagicOS never scrolls/marquees it).
     */
    private fun buildNotification(
        title: String,
        text: String,
        percent: Int?,
        passIndex: Int,
        totalPasses: Int
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, EncodingForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val s = AppLanguageController.currentStrings()
        val safePct = (percent ?: 0).coerceIn(0, 100)
        // Strict <= 4 chars ("%0".."%100" / "0%".."100%") so Honor Magic Capsule displays it completely static
        val capsulePercentChip = s.formatPercent(safePct)
        val drawerSubText = if (totalPasses > 1) "$passIndex/$totalPasses · $capsulePercentChip" else capsulePercentChip

        val celloColor = 0xFF562812.toInt()
        val amberColor = 0xFF844B27.toInt()

        val progressStyle = NotificationCompat.ProgressStyle()
        if (percent == null) {
            progressStyle.setProgressIndeterminate(true)
        } else if (totalPasses > 1) {
            val safeTotal = totalPasses.coerceAtLeast(2)
            val segments = List(safeTotal) { idx ->
                NotificationCompat.ProgressStyle.Segment(100)
                    .setColor(if (idx == 0) amberColor else celloColor)
            }
            progressStyle.setProgressSegments(segments)
            val overallProgress = ((passIndex - 1).coerceAtLeast(0) * 100 + safePct)
                .coerceIn(0, safeTotal * 100)
            progressStyle.setProgress(overallProgress)
            progressStyle.setStyledByProgress(true)
        } else {
            progressStyle.setProgressSegments(
                listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(celloColor))
            )
            progressStyle.setProgress(safePct)
            progressStyle.setStyledByProgress(true)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(passSmallIconRes(passIndex, totalPasses))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(drawerSubText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setColorized(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(capsulePercentChip)
            .setStyle(progressStyle)
            .setContentIntent(openPending)
            .addAction(0, s.actionCancel, cancelPending)

        if (percent != null) {
            builder.setProgress(100, safePct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val s = AppLanguageController.currentStrings()
            val channel = NotificationChannel(
                CHANNEL_ID,
                s.notifChannelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = s.notifChannelDesc
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        lastNotification = null
        if (::cpuBooster.isInitialized) {
            cpuBooster.stop()
        }
        if (::backgroundGuard.isInitialized) {
            backgroundGuard.stop()
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
