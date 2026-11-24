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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.MainActivity
import com.example.R
import com.example.data.FfmpegEngine
import com.example.data.Media3TurboEngine
import com.example.data.MediaStoreSaver
import com.example.data.PreferencesRepository
import com.example.domain.AppLanguageController
import com.example.domain.AppStrings
import com.example.domain.CompletedOutput
import com.example.domain.CustomFfmpegParser
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.TrimSubMode
import com.example.domain.localizedLabel
import com.example.domain.localizedTitle
import com.example.domain.supportsMedia3HardwareTurbo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PendingEncodingJob(
    val runId: Long,
    val media: MediaInfo,
    val config: ModeConfigState
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
        const val ACTION_CANCEL = "com.example.fflab.ACTION_CANCEL_ENCODING"
        private const val EXTRA_CANCELLED_RUN_ID = "extra_cancelled_run_id"

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
            val initialRemux = EncodingState.Running(
                modeTitle = s.turboRemuxModeTitle,
                outputFileName = "turbo_remux.mp4",
                passIndex = 1,
                totalPasses = 1,
                statusMessage = s.turboRemuxStepStatus,
                currentTimeSec = 0.0,
                totalDurationSec = media.durationSec,
                percent = if (media.durationSec != null) 0 else null,
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
                    "${outW}×${outH}"
                }
                media.width != null && media.height != null -> "${media.width}×${media.height}"
                else -> null
            }

            val fpsPart = when {
                supportsFpsAndRes && config.fps == com.example.domain.FpsOption.FPS_30 -> "30 FPS"
                media.fps != null -> {
                    val rounded = kotlin.math.round(media.fps)
                    if (kotlin.math.abs(media.fps - rounded) < 0.08) "${rounded.toInt()} FPS"
                    else "${String.format(java.util.Locale.US, "%.2f", media.fps).trimEnd('0').trimEnd('.')} FPS"
                }
                else -> null
            }

            val bitPart = if (isVideoReencode) {
                if (config.bitDepth == com.example.domain.BitDepthOption.BIT_10) "10b" else "8b"
            } else {
                media.bitShort.ifBlank { "8b" }
            }

            return listOfNotNull(resPart, fpsPart, bitPart)
        }

        /**
         * Unified Turbo strip format:
         * <20MB · H264 635k · AAC · 1080p · 30 FPS · 8b
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
            val audioPart = if (!media.hasAudio) s.bannerSilentAudio else "AAC"
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
                    val mb = FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 20.0
                    listOf("<${FfmpegMath.formatMbTag(mb)}MB", "${config.codec.tag} ${kbps}k", audioPart)
                }
                ProcessingMode.TRIM_VIDEO -> {
                    val (startStr, endStr, _) = FfmpegMath.resolveTrimRange(config, media.durationSec)
                    listOf("${startStr ?: "00:00"}→${endStr ?: s.trimEndPlaceholder}", "${config.codec.tag} ${kbps}k", audioPart)
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
                        else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 20.0
                    }
                    val dur = media.durationSec ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput) ?: 1.0
                    val defAb = FfmpegMath.defaultAudioBitrateForTwoPass(mb)
                    val ab = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
                    val vkbps = FfmpegMath.calculateTwoPassVideoBitrateKbps(mb, dur, ab)
                    listOf("<${FfmpegMath.formatMbTag(mb)}MB", "${config.codec.tag} ${vkbps}k", audioPart)
                }
                ProcessingMode.TRIM_VIDEO -> {
                    val (startStr, endStr, _) = FfmpegMath.resolveTrimRange(config, media.durationSec)
                    val range = "${startStr ?: "00:00"}→${endStr ?: s.trimEndPlaceholder}"
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
                com.example.domain.PresetOption.MEDIUM -> "Medium"
                com.example.domain.PresetOption.SLOW -> "Slow"
                com.example.domain.PresetOption.VERYSLOW -> "Very Slow"
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var cpuBooster: CpuPerformanceBooster
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguageController.init(applicationContext)
        cpuBooster = CpuPerformanceBooster(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val targetRunId = intent.getLongExtra(EXTRA_CANCELLED_RUN_ID, -1L)
                val currentRunId = EncodingJobController.currentActiveRunId()
                if (targetRunId == -1L || currentRunId == 0L || currentRunId == targetRunId) {
                    EncodingJobController.cancelActiveRun()
                    activeJob?.cancel()
                    activeJob = null
                    cpuBooster.stop()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START -> {
                val jobData = EncodingJobController.pendingJob
                if (jobData == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
                startForegroundCompat(
                    buildNotification(
                        title = "FFmpeg · ${jobData.config.mode.localizedTitle(s)}",
                        text = s.notifPreparing,
                        percent = 0,
                        passIndex = 1,
                        totalPasses = initialPasses
                    )
                )
                acquireWakeLock()
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    runEncodingPipeline(jobData.runId, jobData.media, jobData.config)
                }
            }
            ACTION_START_TURBO -> {
                val jobData = EncodingJobController.pendingJob
                if (jobData == null || !EncodingJobController.isRunActive(jobData.runId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                startForegroundCompat(
                    buildNotification(
                        title = "${s.turboNotifTitle} · ${jobData.config.mode.localizedTitle(s)}",
                        text = s.turboStatusPreparing,
                        percent = 0,
                        passIndex = 1,
                        totalPasses = 1
                    )
                )
                acquireWakeLock()
                cpuBooster.stop()
                val previousJob = activeJob
                previousJob?.cancel()
                activeJob = serviceScope.launch {
                    // Allow cancelled FFmpeg native threads and its SAF file descriptor to finish
                    // unwinding cleanly before Media3 opens the same URI and initializes the hardware VPU.
                    // This runs silently underneath the vertical slide animation with zero perceived delay.
                    if (previousJob != null) {
                        withTimeoutOrNull(450L) { runCatching { previousJob.cancelAndJoin() } }
                        delay(160L)
                    }
                    if (!EncodingJobController.isRunActive(jobData.runId)) return@launch
                    runTurboPipeline(
                        runId = jobData.runId,
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
                    stopSelf()
                    return START_NOT_STICKY
                }
                val s = AppLanguageController.currentStrings()
                startForegroundCompat(
                    buildNotification(
                        title = "FFmpeg · ${s.turboRemuxModeTitle}",
                        text = s.turboRemuxStepStatus,
                        percent = 0,
                        passIndex = 1,
                        totalPasses = 1
                    )
                )
                acquireWakeLock()
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    runRemuxThenTurboPipeline(jobData.runId, jobData.media, jobData.config)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runEncodingPipeline(runId: Long, media: MediaInfo, config: ModeConfigState) {
        cpuBooster.start()
        val s = AppLanguageController.currentStrings()
        val workDir = FfmpegEngine.createWorkDir(applicationContext)
        val collectedLogs = mutableListOf<String>()
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
            }
        }

        appendLog("${s.logTagStarted} $modeTitle — $summaryBanner")

        try {
            if (config.mode == ProcessingMode.CUSTOM_COMMAND) {
                runCustomCommandMode(runId, media, config, workDir, modeTitle, summaryBanner, s, collectedLogs, ::appendLog)
                return
            }

            val tempOutFile = File(workDir, expectedOutputName)
            val passLogPrefix = File(workDir, "ff2pass").absolutePath
            val inputPlaceholder = "__FFMPEG_LAZY_INPUT__"
            val steps = FfmpegMath.buildFfmpegSteps(
                media = media,
                config = config,
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
                        logs = synchronized(collectedLogs) { collectedLogs.toList() },
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

                val stepResult = FfmpegEngine.executeStep(
                    args = stepArgs,
                    expectedDurationSec = step.expectedDurationSec,
                    onProgress = { curSec, pct, spd, kb ->
                        if (!EncodingJobController.isRunActive(runId)) return@executeStep
                        cpuBooster.pulseIfDue()
                        val liveStrings = AppLanguageController.currentStrings()
                        EncodingJobController.updateStateIfActive(
                            runId,
                            EncodingState.Running(
                                modeTitle = modeTitle,
                                outputFileName = expectedOutputName,
                                passIndex = passNum,
                                totalPasses = totalPasses,
                                statusMessage = step.label,
                                currentTimeSec = curSec,
                                totalDurationSec = step.expectedDurationSec,
                                percent = pct,
                                speed = spd,
                                sizeKb = kb,
                                logs = synchronized(collectedLogs) { collectedLogs.toList() },
                                summaryBanner = summaryBanner,
                                videoSpecsSummary = videoSpecsSummary,
                                presetLabel = presetLabel,
                                canSwitchToTurbo = canSwitchToTurbo
                            )
                        )
                        val notifText = FfmpegMath.formatTelemetryLine(
                            currentSec = curSec,
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
                        val cur = EncodingJobController.state.value
                        if (cur is EncodingState.Running) {
                            EncodingJobController.updateStateIfActive(
                                runId,
                                cur.copy(logs = synchronized(collectedLogs) { collectedLogs.toList() })
                            )
                        }
                    }
                )

                if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                    return
                }

                if (!stepResult.success) {
                    val curS = AppLanguageController.currentStrings()
                    appendLog("${curS.logTagError} ${curS.errFfmpegExitCode(stepResult.returnCode)}")
                    val hint = resolveContextualHint(config, curS)
                    EncodingJobController.updateStateIfActive(
                        runId,
                        EncodingState.Failed(
                            modeTitle = modeTitle,
                            errorMessage = curS.errFfmpegExitCode(stepResult.returnCode),
                            hint = hint,
                            logs = synchronized(collectedLogs) { collectedLogs.toList() },
                            wasCancelled = false
                        )
                    )
                    return
                }
            }

            if (!EncodingJobController.isRunActive(runId)) return

            if (!tempOutFile.exists() || tempOutFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                appendLog("${curS.logTagError} ${curS.errOutputNotCreated}")
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.errOutputNotCreated,
                        hint = resolveContextualHint(config, curS),
                        logs = synchronized(collectedLogs) { collectedLogs.toList() }
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
                    logs = synchronized(collectedLogs) { collectedLogs.toList() }
                )
            )
        } catch (_: CancellationException) {
            // Cancelled cleanly: keep state Idle and let finally clean resources
        } catch (t: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                appendLog("${curS.logTagError} ${t.message ?: curS.errUnexpected}")
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = t.message ?: curS.errUnexpected,
                        hint = resolveContextualHint(config, curS),
                        logs = synchronized(collectedLogs) { collectedLogs.toList() }
                    )
                )
            }
        } finally {
            // Zero-bloat guarantee: always wipe temp workDir (.log, .cutree, .mbtree, partial outputs)
            runCatching { workDir.deleteRecursively() }
            cpuBooster.stop()
            // Only tear down ForegroundService & WakeLock if another run (e.g. Media3 Turbo) hasn't taken over
            if (EncodingJobController.isRunActive(runId) || EncodingJobController.currentActiveRunId() == 0L) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * One-time automatic lossless MP4 remux via FFmpeg (-c copy) followed immediately (without
     * asking the user again) by sliding back to the Media3 car speedometer screen to retry.
     */
    private suspend fun runRemuxThenTurboPipeline(
        runId: Long,
        media: MediaInfo,
        config: ModeConfigState
    ) {
        cpuBooster.start()
        val s = AppLanguageController.currentStrings()
        val remuxWorkDir = FfmpegEngine.createWorkDir(applicationContext)
        val remuxFile = File(remuxWorkDir, "turbo_remux.mp4")
        val collectedLogs = mutableListOf<String>()
        val modeLocalizedTitle = config.mode.localizedTitle(s)
        val modeTitle = "${config.mode.formattedNumber} · $modeLocalizedTitle"
        val specsSummary = buildTurboUnifiedSpecs(media, config, s)

        fun appendLog(line: String) {
            synchronized(collectedLogs) {
                if (collectedLogs.lastOrNull() == line) return
                if (collectedLogs.size >= 80) collectedLogs.removeAt(0)
                collectedLogs.add(line)
            }
        }

        appendLog("${s.logTagStarted} ${s.turboRemuxModeTitle} — ${s.bannerRemux}")
        var handedOffToTurbo = false

        try {
            val inputPath = FfmpegEngine.resolveInputPath(applicationContext, media.uri)
            val remuxArgs = listOf(
                "-y",
                "-i", inputPath,
                "-map", "0:v:0",
                "-map", "0:a:0?",
                "-c", "copy",
                "-sn", "-dn",
                "-avoid_negative_ts", "make_zero",
                "-max_muxing_queue_size", "1024",
                "-movflags", "+faststart",
                remuxFile.absolutePath
            )

            val stepResult = FfmpegEngine.executeStep(
                args = remuxArgs,
                expectedDurationSec = media.durationSec,
                onProgress = { curSec, pct, spd, kb ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    cpuBooster.pulseIfDue()
                    val liveS = AppLanguageController.currentStrings()
                    EncodingJobController.updateStateIfActive(
                        runId,
                        EncodingState.Running(
                            modeTitle = liveS.turboRemuxModeTitle,
                            outputFileName = remuxFile.name,
                            passIndex = 1,
                            totalPasses = 1,
                            statusMessage = liveS.turboRemuxStepStatus,
                            currentTimeSec = curSec,
                            totalDurationSec = media.durationSec,
                            percent = pct,
                            speed = spd,
                            sizeKb = kb,
                            logs = synchronized(collectedLogs) { collectedLogs.toList() },
                            summaryBanner = liveS.bannerRemux,
                            videoSpecsSummary = null,
                            presetLabel = null,
                            isHardwareTurbo = false,
                            canSwitchToTurbo = false,
                            isTurboPreRemux = true
                        )
                    )
                    updateNotificationThrottled(
                        runId = runId,
                        title = "FFmpeg · ${liveS.turboRemuxModeTitle}",
                        text = FfmpegMath.formatTelemetryLine(curSec, media.durationSec, pct, spd, kb, includePercent = false, strings = liveS),
                        percent = pct,
                        passIndex = 1,
                        totalPasses = 1,
                        force = false
                    )
                },
                onLogLine = { line ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    appendLog(line)
                    val cur = EncodingJobController.state.value
                    if (cur is EncodingState.Running && !cur.isHardwareTurbo) {
                        EncodingJobController.updateStateIfActive(
                            runId,
                            cur.copy(logs = synchronized(collectedLogs) { collectedLogs.toList() })
                        )
                    }
                }
            )

            if (!EncodingJobController.isRunActive(runId) || stepResult.cancelled) {
                return
            }

            if (!stepResult.success || !remuxFile.exists() || remuxFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
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
                        hasTriedRemux = true
                    )
                )
                return
            }

            // Lossless remux succeeded! Slide automatically back to the speedometer UI (Slide 2)
            // without prompting the user again, and retry Media3 hardware encoding on the remuxed MP4.
            cpuBooster.stop()
            val curS = AppLanguageController.currentStrings()
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
            handedOffToTurbo = true
            runTurboPipeline(
                runId = runId,
                media = media,
                config = config,
                inputUriOverride = Uri.fromFile(remuxFile),
                hasTriedRemux = true,
                extraDirToCleanup = remuxWorkDir
            )
        } catch (_: CancellationException) {
            // Cancelled cleanly
        } catch (_: Throwable) {
            if (EncodingJobController.isRunActive(runId)) {
                val curS = AppLanguageController.currentStrings()
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.turboAfterRemuxFailedBody,
                        hint = curS.turboAfterRemuxFastHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = true,
                        hasTriedRemux = true
                    )
                )
            }
        } finally {
            cpuBooster.stop()
            if (!handedOffToTurbo) {
                runCatching { remuxWorkDir.deleteRecursively() }
                if (EncodingJobController.isRunActive(runId) || EncodingJobController.currentActiveRunId() == 0L) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runTurboPipeline(
        runId: Long,
        media: MediaInfo,
        config: ModeConfigState,
        inputUriOverride: Uri?,
        hasTriedRemux: Boolean,
        extraDirToCleanup: File?
    ) {
        EncodingJobController.markTurboEngineActive(true)
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

        try {
            val tempOutFile = File(workDir, turboOutputName)
            var lastKnownPercent = 0

            val result = Media3TurboEngine.transcode(
                context = applicationContext,
                media = media,
                config = config,
                outputFile = tempOutFile,
                inputUriOverride = inputUriOverride,
                onPlanResolved = { plan ->
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
                    if (effectiveDur > 0.0) {
                        resolvedDurationSec = effectiveDur
                    }
                    if (pct >= 0) {
                        lastKnownPercent = pct.coerceIn(0, 100)
                    }
                    val statusMsg = if (pct < 0 && lastKnownPercent == 0) {
                        liveS.turboStatusPreparing
                    } else {
                        liveS.turboStatusEncoding
                    }
                    val totalDur = resolvedDurationSec.takeIf { it > 0.0 }
                    val processedSec = if (totalDur != null) {
                        (totalDur * (lastKnownPercent / 100.0)).coerceIn(0.0, totalDur)
                    } else {
                        elapsedSec
                    }
                    val speedStr = if (totalDur != null && elapsedSec >= 0.4 && processedSec >= 0.05) {
                        String.format(java.util.Locale.US, "%.2fx", processedSec / elapsedSec)
                    } else {
                        "..."
                    }
                    lastKnownSpeed = speedStr
                    val liveSizeKb = if (tempOutFile.exists()) tempOutFile.length() / 1024L else 0L

                    EncodingJobController.updateStateIfActive(
                        runId,
                        EncodingState.Running(
                            modeTitle = modeTitle,
                            outputFileName = turboOutputName,
                            passIndex = 1,
                            totalPasses = 1,
                            statusMessage = statusMsg,
                            currentTimeSec = processedSec,
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
                            elapsedSec = elapsedSec
                        )
                    )
                    val notifTelemetry = FfmpegMath.formatTelemetryLine(
                        currentSec = processedSec,
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
                val detailSuffix = listOfNotNull(result.errorCodeName, result.errorDetail)
                    .filter { it.isNotBlank() }
                    .joinToString(" — ")
                val errorMessage = when {
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
                    hasTriedRemux -> curS.turboAfterRemuxFastHint
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
                        isUnsupportedFormat = result.isUnsupportedFormat || hasTriedRemux,
                        hasTriedRemux = hasTriedRemux
                    )
                )
                return
            }

            if (!tempOutFile.exists() || tempOutFile.length() == 0L) {
                val curS = AppLanguageController.currentStrings()
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = if (hasTriedRemux) curS.turboAfterRemuxFailedBody else curS.errOutputNotCreated,
                        hint = if (hasTriedRemux) curS.turboAfterRemuxFastHint else curS.turboErrorGenericHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = hasTriedRemux,
                        hasTriedRemux = hasTriedRemux
                    )
                )
                return
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
                    sizeKb = tempOutFile.length() / 1024L,
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
                sourceFile = tempOutFile,
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
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = if (hasTriedRemux) curS.turboAfterRemuxFailedBody else (t.message ?: curS.errUnexpected),
                        hint = if (hasTriedRemux) curS.turboAfterRemuxFastHint else curS.turboErrorGenericHint,
                        logs = emptyList(),
                        isHardwareTurbo = true,
                        isUnsupportedFormat = hasTriedRemux,
                        hasTriedRemux = hasTriedRemux
                    )
                )
            }
        } finally {
            EncodingJobController.markTurboEngineActive(false)
            runCatching { workDir.deleteRecursively() }
            runCatching { extraDirToCleanup?.deleteRecursively() }
            if (EncodingJobController.isRunActive(runId) || EncodingJobController.currentActiveRunId() == 0L) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
        collectedLogs: MutableList<String>,
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
            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Failed(
                    modeTitle = modeTitle,
                    errorMessage = s.errCustomCmdEmpty,
                    hint = s.hintCustomCmdExample,
                    logs = synchronized(collectedLogs) { collectedLogs.toList() }
                )
            )
            return
        }

        val totalSteps = plan.steps.size
        val displayOutName = plan.primaryFinalOutputName ?: s.customCmdOutputLabel
        for ((idx, step) in plan.steps.withIndex()) {
            if (!EncodingJobController.isRunActive(runId)) return
            val resolvedArgs = step.args.map { arg ->
                if (arg == inputPlaceholder) {
                    FfmpegEngine.resolveInputPath(applicationContext, media.uri)
                } else {
                    arg
                }
            }
            val passNum = idx + 1
            val label = if (totalSteps > 1) s.customCmdStepMulti(passNum, totalSteps) else s.customCmdStepSingle
            val stepExpectedDur = step.expectedDurationSec ?: media.durationSec
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
                    logs = synchronized(collectedLogs) { collectedLogs.toList() },
                    summaryBanner = summaryBanner
                )
            )
            updateNotificationThrottled(
                runId = runId,
                title = if (totalSteps > 1) "FFmpeg · ($passNum/$totalSteps) ${s.customCmdOutputLabel}" else "FFmpeg · ${s.customCmdOutputLabel}",
                text = label,
                percent = 0,
                passIndex = passNum,
                totalPasses = totalSteps,
                force = true
            )

            val res = FfmpegEngine.executeStep(
                args = resolvedArgs,
                expectedDurationSec = stepExpectedDur,
                onProgress = { curSec, pct, spd, kb ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    cpuBooster.pulseIfDue()
                    val liveS = AppLanguageController.currentStrings()
                    EncodingJobController.updateStateIfActive(
                        runId,
                        EncodingState.Running(
                            modeTitle = modeTitle,
                            outputFileName = displayOutName,
                            passIndex = passNum,
                            totalPasses = totalSteps,
                            statusMessage = label,
                            currentTimeSec = curSec,
                            totalDurationSec = stepExpectedDur,
                            percent = pct,
                            speed = spd,
                            sizeKb = kb,
                            logs = synchronized(collectedLogs) { collectedLogs.toList() },
                            summaryBanner = summaryBanner
                        )
                    )
                    val titlePrefix = if (totalSteps > 1) "($passNum/$totalSteps) " else ""
                    updateNotificationThrottled(
                        runId = runId,
                        title = "FFmpeg · $titlePrefix${liveS.customCmdOutputLabel}",
                        text = FfmpegMath.formatTelemetryLine(curSec, stepExpectedDur, pct, spd, kb, includePercent = false, strings = liveS),
                        percent = pct,
                        passIndex = passNum,
                        totalPasses = totalSteps,
                        force = false
                    )
                },
                onLogLine = { line ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    appendLog(line)
                    val cur = EncodingJobController.state.value
                    if (cur is EncodingState.Running) {
                        EncodingJobController.updateStateIfActive(
                            runId,
                            cur.copy(logs = synchronized(collectedLogs) { collectedLogs.toList() })
                        )
                    }
                }
            )

            if (!EncodingJobController.isRunActive(runId) || res.cancelled) {
                return
            }
            if (!res.success) {
                val curS = AppLanguageController.currentStrings()
                appendLog("${curS.logTagError} ${curS.errCustomCmdFailed(res.returnCode)}")
                EncodingJobController.updateStateIfActive(
                    runId,
                    EncodingState.Failed(
                        modeTitle = modeTitle,
                        errorMessage = curS.errCustomCmdFailed(res.returnCode),
                        hint = curS.hintCustomCmdSyntax(media.extension),
                        logs = synchronized(collectedLogs) { collectedLogs.toList() }
                    )
                )
                return
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
                n.endsWith(".log.cutree") ||
                n.endsWith(".cutree") ||
                n.endsWith(".mbtree") ||
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

        val summary = if (savedOutputs.isEmpty()) {
            curS.successCustomCmdDoneNoFiles
        } else {
            val totalBytes = savedOutputs.sumOf { it.outputSizeBytes }
            curS.successCustomCmdSavedFiles(savedOutputs.size, FfmpegMath.formatSizeBytes(totalBytes))
        }

        appendLog("${curS.logTagCompleted} $summary")
        EncodingJobController.updateStateIfActive(
            runId,
            EncodingState.Success(
                outputs = savedOutputs,
                modeTitle = modeTitle,
                summaryMessage = summary,
                logs = synchronized(collectedLogs) { collectedLogs.toList() }
            )
        )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs < 500L) return
        lastNotifyMs = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text, percent, passIndex, totalPasses))
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

    private fun acquireWakeLock() {
        releaseWakeLock()
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FFmpeg:EncodingWakeLock").apply {
                // Safety timeout: 4 hours max for huge 4K veryslow encodes
                acquire(4L * 60L * 60L * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        if (::cpuBooster.isInitialized) {
            cpuBooster.stop()
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
