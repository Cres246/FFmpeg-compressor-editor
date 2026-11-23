package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.MainActivity
import com.example.R
import com.example.data.FfmpegEngine
import com.example.data.MediaStoreSaver
import com.example.data.PreferencesRepository
import com.example.domain.AppLanguageController
import com.example.domain.AppStrings
import com.example.domain.CompletedOutput
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.TrimSubMode
import com.example.domain.localizedLabel
import com.example.domain.localizedTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
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

    @Volatile
    private var activeRunId: Long = 0L

    @Volatile
    private var cancelledRunId: Long = -1L

    @Volatile
    var pendingJob: PendingEncodingJob? = null

    fun beginNewRun(): Long {
        val nextId = runCounter.incrementAndGet()
        activeRunId = nextId
        return nextId
    }

    fun isRunActive(runId: Long): Boolean {
        return runId > 0L && activeRunId == runId && cancelledRunId != runId
    }

    fun updateStateIfActive(runId: Long, newState: EncodingState) {
        if (isRunActive(runId)) {
            _state.value = newState
        }
    }

    /**
     * Atomically invalidates the active run so no late FFmpegKit progress/log/error callback can
     * ever reopen ProcessingScreen or trap the user, cancels native FFmpeg, and returns to Idle.
     */
    fun cancelActiveRun() {
        val current = activeRunId
        if (current > 0L) {
            cancelledRunId = current
        }
        activeRunId = 0L
        pendingJob = null
        _state.value = EncodingState.Idle
        _cancelGeneration.value = _cancelGeneration.value + 1L
        runCatching { FFmpegKit.cancel() }
    }

    fun forceResetToIdle() {
        _state.value = EncodingState.Idle
    }
}

class EncodingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "fflab_live_encoding_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.fflab.ACTION_START_ENCODING"
        const val ACTION_CANCEL = "com.example.fflab.ACTION_CANCEL_ENCODING"

        fun startEncoding(context: Context, media: MediaInfo, config: ModeConfigState) {
            val runId = EncodingJobController.beginNewRun()
            EncodingJobController.pendingJob = PendingEncodingJob(runId, media, config)
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelEncoding(context: Context) {
            EncodingJobController.cancelActiveRun()
            val intent = Intent(context, EncodingForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching { context.startService(intent) }
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
                EncodingJobController.cancelActiveRun()
                activeJob?.cancel()
                activeJob = null
                cpuBooster.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
        val videoSpecsSummary = buildVideoSpecsSummary(media, config)
        val presetLabel = resolvePresetDisplayLabel(config)

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
                        presetLabel = presetLabel
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
                                presetLabel = presetLabel
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
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        val commands = FfmpegMath.parseCustomCommandChain(config.customCommandInput)
        if (commands.isEmpty()) {
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

        val totalSteps = commands.size
        for ((idx, rawTokens) in commands.withIndex()) {
            if (!EncodingJobController.isRunActive(runId)) return
            val resolvedArgs = FfmpegMath.resolveCustomCommandArgs(
                tokens = rawTokens,
                media = media,
                workDirPath = workDir.absolutePath,
                inputTokenProvider = { FfmpegEngine.resolveInputPath(applicationContext, media.uri) }
            )
            val passNum = idx + 1
            val label = if (totalSteps > 1) s.customCmdStepMulti(passNum, totalSteps) else s.customCmdStepSingle
            if (totalSteps > 1) {
                appendLog("${s.logTagStep(passNum, totalSteps)} $label")
            }

            EncodingJobController.updateStateIfActive(
                runId,
                EncodingState.Running(
                    modeTitle = modeTitle,
                    outputFileName = s.customCmdOutputLabel,
                    passIndex = passNum,
                    totalPasses = totalSteps,
                    statusMessage = label,
                    currentTimeSec = 0.0,
                    totalDurationSec = media.durationSec,
                    percent = null,
                    speed = "...",
                    sizeKb = 0L,
                    logs = synchronized(collectedLogs) { collectedLogs.toList() },
                    summaryBanner = summaryBanner
                )
            )
            updateNotificationThrottled(
                runId = runId,
                title = "FFmpeg · ${s.customCmdOutputLabel}",
                text = label,
                percent = 0,
                passIndex = passNum,
                totalPasses = totalSteps,
                force = true
            )

            val res = FfmpegEngine.executeStep(
                args = resolvedArgs,
                expectedDurationSec = media.durationSec,
                onProgress = { curSec, pct, spd, kb ->
                    if (!EncodingJobController.isRunActive(runId)) return@executeStep
                    cpuBooster.pulseIfDue()
                    val liveS = AppLanguageController.currentStrings()
                    EncodingJobController.updateStateIfActive(
                        runId,
                        EncodingState.Running(
                            modeTitle = modeTitle,
                            outputFileName = liveS.customCmdOutputLabel,
                            passIndex = passNum,
                            totalPasses = totalSteps,
                            statusMessage = label,
                            currentTimeSec = curSec,
                            totalDurationSec = media.durationSec,
                            percent = pct,
                            speed = spd,
                            sizeKb = kb,
                            logs = synchronized(collectedLogs) { collectedLogs.toList() },
                            summaryBanner = summaryBanner
                        )
                    )
                    updateNotificationThrottled(
                        runId = runId,
                        title = "FFmpeg · ${liveS.customCmdOutputLabel}",
                        text = FfmpegMath.formatTelemetryLine(curSec, media.durationSec, pct, spd, kb, includePercent = false, strings = liveS),
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
        // Clean 2-pass log artifacts exactly like Option 10 in the script
        val savedOutputs = mutableListOf<CompletedOutput>()
        workDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val n = file.name
            val isTempPassArtifact = n.endsWith(".log") ||
                n.endsWith(".log.temp") ||
                n.endsWith(".cutree") ||
                n.endsWith(".mbtree") ||
                n.startsWith("ffmpeg2pass")
            if (isTempPassArtifact) {
                file.delete()
            } else if (file.length() > 0L) {
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

    private fun buildSummaryBanner(media: MediaInfo, config: ModeConfigState, s: AppStrings): String {
        return when (config.mode) {
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
                s.twoPassSummaryLine(
                    FfmpegMath.formatMbTag(mb),
                    config.codec.tag,
                    config.bitDepth.tag,
                    vkbps,
                    ab,
                    config.preset.value
                )
            }
            ProcessingMode.TRIM_VIDEO -> {
                val (startStr, endStr, _) = FfmpegMath.resolveTrimRange(config, media.durationSec)
                s.bannerTrimRange(startStr ?: "00:00", endStr ?: s.trimEndPlaceholder, config.trimSubMode.localizedTitle(s))
            }
            ProcessingMode.REMUX_FORMAT -> s.bannerRemux
            ProcessingMode.AUDIO_ONLY_AAC -> {
                val ab = FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, 256)
                s.bannerAudioOnly(ab)
            }
            ProcessingMode.VIDEO_ONLY_CRF ->
                s.bannerVideoOnly(config.codec.tag, config.bitDepth.tag, config.codec.crf, config.preset.value)
            ProcessingMode.VIDEO_AND_AUDIO_CRF -> {
                val ab = if (!media.hasAudio) s.bannerSilentAudio else "${FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, 256)}k AAC"
                "${config.codec.tag}${config.bitDepth.tag} CRF ${config.codec.crf} (${config.preset.value}) | $ab"
            }
            ProcessingMode.EXTRACT_AUDIO -> s.bannerExtractAudio(config.audioExtractFormat.localizedLabel(s))
            ProcessingMode.CUSTOM_COMMAND -> s.bannerCustomCmd
        }
    }

    private fun buildVideoSpecsSummary(media: MediaInfo, config: ModeConfigState): String? {
        if (!media.hasVideo ||
            config.mode == ProcessingMode.EXTRACT_AUDIO ||
            config.mode == ProcessingMode.CUSTOM_COMMAND
        ) return null

        val isTwoPass = config.mode == ProcessingMode.DISCORD_20MB ||
            config.mode == ProcessingMode.WHATSAPP_100MB ||
            config.mode == ProcessingMode.GMAIL_25MB ||
            config.mode == ProcessingMode.CUSTOM_SIZE
        val isTrimCrf = config.mode == ProcessingMode.TRIM_VIDEO &&
            (config.trimSubMode == com.example.domain.TrimSubMode.CRF_AUDIO_COPY ||
                config.trimSubMode == com.example.domain.TrimSubMode.CRF_AUDIO_AAC)
        val isVideoReencode = isTwoPass ||
            isTrimCrf ||
            config.mode == ProcessingMode.VIDEO_ONLY_CRF ||
            config.mode == ProcessingMode.VIDEO_AND_AUDIO_CRF
        val supportsFpsAndRes = isTwoPass || isTrimCrf

        val resText = when {
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
            media.width != null && media.height != null -> "${media.width}×${media.height}"
            else -> null
        }

        val fpsText = when {
            supportsFpsAndRes && config.fps == com.example.domain.FpsOption.FPS_30 -> "30 FPS"
            media.fps != null -> {
                val rounded = kotlin.math.round(media.fps)
                if (kotlin.math.abs(media.fps - rounded) < 0.08) {
                    "${rounded.toInt()} FPS"
                } else {
                    val fmt = String.format(java.util.Locale.US, "%.2f", media.fps)
                        .trimEnd('0')
                        .trimEnd('.')
                    "$fmt FPS"
                }
            }
            else -> null
        }

        val bitText = if (isVideoReencode) {
            config.bitDepth.label
        } else {
            when (media.bitDepthIn) {
                "10bit" -> "10-bit"
                "12bit" -> "12-bit"
                else -> "8-bit"
            }
        }

        return listOfNotNull(resText, fpsText, bitText).joinToString(" · ").ifBlank { null }
    }

    private fun resolvePresetDisplayLabel(config: ModeConfigState): String? {
        val usesPreset = when (config.mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE,
            ProcessingMode.VIDEO_ONLY_CRF,
            ProcessingMode.VIDEO_AND_AUDIO_CRF -> true
            ProcessingMode.TRIM_VIDEO -> config.trimSubMode == com.example.domain.TrimSubMode.CRF_AUDIO_COPY ||
                config.trimSubMode == com.example.domain.TrimSubMode.CRF_AUDIO_AAC
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
