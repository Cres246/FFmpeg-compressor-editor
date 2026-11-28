package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FfmpegEngine
import com.example.data.PreferencesRepository
import com.example.domain.AppLanguage
import com.example.domain.AppLanguageController
import com.example.domain.AudioExtractFormat
import com.example.domain.BitDepthOption
import com.example.domain.CompletedOutput
import com.example.domain.EncoderCapabilities
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.FpsOption
import com.example.domain.LanguagePreference
import com.example.domain.Media3CompatibilityAdvisor
import com.example.domain.MediaInfo
import com.example.domain.MediaPickSource
import com.example.domain.ModeConfigState
import com.example.domain.PendingTurboContinuation
import com.example.domain.PresetOption
import com.example.domain.ProcessingMode
import com.example.domain.RemuxOutputFormat
import com.example.domain.ResolutionOption
import com.example.domain.SubtitleCue
import com.example.domain.SubtitleEmbedMode
import com.example.domain.SubtitleFontOption
import com.example.domain.SubtitleFontSizeOption
import com.example.domain.SubtitleSoftsubContainer
import com.example.domain.SubtitleTrackInfo
import com.example.domain.TrimCutStrategy
import com.example.domain.TrimScanState
import com.example.domain.TrimSubMode
import com.example.domain.TrimTimeChoice
import com.example.domain.VideoCodecOption
import com.example.service.EncodingForegroundService
import com.example.service.EncodingJobController
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class WorkbenchUiState(
    val mediaInfo: MediaInfo? = null,
    val lastMediaPickSource: MediaPickSource = MediaPickSource.FILE_PICKER,
    val isProbing: Boolean = false,
    val probeError: String? = null,
    val capabilities: EncoderCapabilities = EncoderCapabilities(),
    val activeConfig: ModeConfigState? = null,
    val pendingTurboContinuation: PendingTurboContinuation? = null,
    val validationError: String? = null,
    val audioBitrateTouched: Boolean = false,
    val fpsTouched: Boolean = false,
    val resolutionTouched: Boolean = false,
    val isLoadingSubtitle: Boolean = false,
    val showHistorySheet: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val prefsRepo = PreferencesRepository(appContext)

    private val _uiState = MutableStateFlow(WorkbenchUiState())
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()
    private var probeErrorAutoClearJob: Job? = null
    private var mediaProbeJob: Job? = null
    private var keyframeScanJob: Job? = null
    private var subtitleLoadJob: Job? = null

    val encodingState: StateFlow<EncodingState> = EncodingJobController.state
    val appLanguage: StateFlow<AppLanguage> = AppLanguageController.language
    val languagePreference: StateFlow<LanguagePreference> = AppLanguageController.preference

    val history: StateFlow<List<CompletedOutput>> = prefsRepo.historyFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        AppLanguageController.init(appContext)
        // Restore active media/config if Activity was recreated while a foreground job is running/finished
        val activeSnapshot = EncodingJobController.activeJobSnapshot
        if (activeSnapshot != null && EncodingJobController.state.value !is EncodingState.Idle) {
            _uiState.update {
                it.copy(
                    mediaInfo = activeSnapshot.media,
                    activeConfig = activeSnapshot.config
                )
            }
        }
        viewModelScope.launch {
            FfmpegEngine.cleanupOrphanTempDirsIfIdle(appContext) {
                EncodingJobController.currentActiveRunId() == 0L &&
                    EncodingJobController.state.value is EncodingState.Idle
            }
            val caps = FfmpegEngine.probeEncoderCapabilities()
            _uiState.update { it.copy(capabilities = caps) }
        }
        viewModelScope.launch {
            var lastGen = EncodingJobController.cancelGeneration.value
            EncodingJobController.cancelGeneration.collect { gen ->
                if (gen != lastGen) {
                    lastGen = gen
                    _uiState.update {
                        it.copy(
                            activeConfig = null,
                            pendingTurboContinuation = null,
                            validationError = null
                        )
                    }
                }
            }
        }
    }

    fun setLanguagePreference(pref: LanguagePreference) {
        AppLanguageController.setPreference(appContext, pref)
        _uiState.update { it.copy(probeError = null, validationError = null) }
    }

    fun onMediaSelected(uri: Uri, source: MediaPickSource? = null) {
        val currentEncoding = EncodingJobController.state.value
        if (currentEncoding is EncodingState.Running) {
            showProbeError(AppLanguageController.currentStrings().errEncodingInProgress)
            return
        }
        if (currentEncoding !is EncodingState.Idle) {
            EncodingJobController.forceResetToIdle()
        }
        keyframeScanJob?.cancel()
        keyframeScanJob = null
        mediaProbeJob?.cancel()
        mediaProbeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lastMediaPickSource = source ?: it.lastMediaPickSource,
                    isProbing = true,
                    probeError = null,
                    activeConfig = null,
                    pendingTurboContinuation = null,
                    validationError = null
                )
            }
            try {
                val info = FfmpegEngine.probeMedia(appContext, uri)
                _uiState.update {
                    it.copy(
                        mediaInfo = info,
                        isProbing = false,
                        probeError = null
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val s = AppLanguageController.currentStrings()
                _uiState.update { it.copy(isProbing = false) }
                showProbeError(s.errAnalyzeFailed(FfmpegEngine.describeError(t)))
            }
        }
    }

    fun selectMode(mode: ProcessingMode) {
        val s = AppLanguageController.currentStrings()
        val media = _uiState.value.mediaInfo
        if (media == null) {
            showProbeError(s.errSelectMediaFirst)
            return
        }
        if (mode.requiresVideo && !media.hasVideo) {
            showProbeError(s.errNoVideoTrack)
            return
        }
        if (mode.requiresAudio && !media.hasAudio) {
            showProbeError(s.errNoAudioTrack)
            return
        }

        val defaultTargetMb = when (mode) {
            ProcessingMode.DISCORD_20MB -> "20"
            ProcessingMode.WHATSAPP_100MB -> "100"
            ProcessingMode.GMAIL_25MB -> "25"
            ProcessingMode.CUSTOM_SIZE -> "8"
            else -> "20"
        }
        val defaultPreset = PresetOption.FAST
        val defaultFps = FpsOption.ORIGINAL
        val baseAudioKbps = when (mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> 128
            ProcessingMode.TRIM_VIDEO -> 192
            else -> 256
        }
        val defaultAudioKbps = FfmpegMath.resolveDefaultAudioBitrate(
            baseDefaultKbps = baseAudioKbps,
            sourceKbps = media.audioBitrateKbps,
            isLosslessSource = media.isLosslessAudio,
            maxKbps = 768
        ).toString()

        val defaultSubEmbedMode = if (_uiState.value.capabilities.libassSupportsHardsub) {
            SubtitleEmbedMode.HARDSUB
        } else {
            SubtitleEmbedMode.SOFTSUB
        }

        val initialConfig = ModeConfigState(
            mode = mode,
            targetMbInput = defaultTargetMb,
            codec = VideoCodecOption.H264,
            bitDepth = BitDepthOption.BIT_8,
            fps = defaultFps,
            resolution = ResolutionOption.ORIGINAL,
            preset = defaultPreset,
            audioBitrateInput = defaultAudioKbps,
            trimSubMode = TrimSubMode.FULL_LOSSLESS,
            audioExtractFormat = AudioExtractFormat.ORIGINAL,
            preserveSubtitles = false,
            remuxFormat = RemuxOutputFormat.MP4,
            subtitleEmbedMode = defaultSubEmbedMode,
            customCommandInput = ""
        )

        keyframeScanJob?.cancel()
        keyframeScanJob = null
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        _uiState.update {
            it.copy(
                activeConfig = initialConfig,
                pendingTurboContinuation = null,
                validationError = null,
                audioBitrateTouched = false,
                fpsTouched = false,
                resolutionTouched = false,
                isLoadingSubtitle = false,
                probeError = null
            )
        }
    }

    fun closeConfig() {
        keyframeScanJob?.cancel()
        keyframeScanJob = null
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        _uiState.update {
            it.copy(
                activeConfig = null,
                pendingTurboContinuation = null,
                validationError = null,
                isLoadingSubtitle = false
            )
        }
    }

    fun updateTargetMb(value: String) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            val media = state.mediaInfo
            val parsedMb = FfmpegMath.parsePositiveDouble(value)
            val newAudio = if (!state.audioBitrateTouched && parsedMb != null) {
                val baseAudio = FfmpegMath.defaultAudioBitrateForTwoPass(parsedMb)
                FfmpegMath.resolveDefaultAudioBitrate(
                    baseDefaultKbps = baseAudio,
                    sourceKbps = media?.audioBitrateKbps,
                    isLosslessSource = media?.isLosslessAudio == true,
                    maxKbps = 768
                ).toString()
            } else {
                cfg.audioBitrateInput
            }
            val newFps = if (!state.fpsTouched && parsedMb != null) {
                if (parsedMb < 5.0) FpsOption.FPS_30 else FpsOption.ORIGINAL
            } else {
                cfg.fps
            }
            val newResolution = if (!state.resolutionTouched && parsedMb != null) {
                val shortEdge = if (media?.width != null && media.height != null) {
                    minOf(media.width, media.height)
                } else null
                if (parsedMb < 5.0 && (shortEdge == null || shortEdge > 720)) {
                    ResolutionOption.P720
                } else {
                    ResolutionOption.ORIGINAL
                }
            } else {
                cfg.resolution
            }
            state.copy(
                activeConfig = cfg.copy(
                    targetMbInput = value,
                    audioBitrateInput = newAudio,
                    fps = newFps,
                    resolution = newResolution
                ),
                validationError = null
            )
        }
    }

    fun updateManualDuration(value: String) {
        updateConfig { it.copy(manualDurationInput = value) }
    }

    fun updateCodec(codec: VideoCodecOption) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            val media = state.mediaInfo
            val caps = state.capabilities
            val recommendedBitDepth = when (codec) {
                VideoCodecOption.H265 -> {
                    val inputHighBit = media?.bitDepthIn == "10bit" || media?.bitDepthIn == "12bit"
                    if (inputHighBit && caps.x265Supports10Bit) BitDepthOption.BIT_10 else BitDepthOption.BIT_8
                }
                VideoCodecOption.H264 -> BitDepthOption.BIT_8
            }
            state.copy(
                activeConfig = cfg.copy(codec = codec, bitDepth = recommendedBitDepth),
                validationError = null
            )
        }
    }

    fun updateBitDepth(bitDepth: BitDepthOption) {
        updateConfig { cfg ->
            val safeBitDepth = if (cfg.codec == VideoCodecOption.H264) BitDepthOption.BIT_8 else bitDepth
            cfg.copy(bitDepth = safeBitDepth)
        }
    }

    fun updateFps(fps: FpsOption) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            state.copy(
                activeConfig = cfg.copy(fps = fps),
                fpsTouched = true,
                validationError = null
            )
        }
    }

    fun updateResolution(resolution: ResolutionOption) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            state.copy(
                activeConfig = cfg.copy(resolution = resolution),
                resolutionTouched = true,
                validationError = null
            )
        }
    }

    fun updatePreset(preset: PresetOption) {
        updateConfig { it.copy(preset = preset) }
    }

    fun updateAudioBitrate(value: String) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            state.copy(
                activeConfig = cfg.copy(audioBitrateInput = value),
                audioBitrateTouched = true,
                validationError = null
            )
        }
    }

    fun updateStartTime(value: String) {
        val curCfg = _uiState.value.activeConfig
        val autoRefresh = curCfg != null &&
            curCfg.mode == ProcessingMode.TRIM_VIDEO &&
            curCfg.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME &&
            curCfg.trimScan.panelExpanded
        val isBlank = value.trim().isEmpty()

        keyframeScanJob?.cancel()
        keyframeScanJob = null
        updateConfig {
            it.copy(
                startTimeInput = value,
                selectedStartKfSec = null,
                trimScan = it.trimScan.copy(
                    isScanning = false,
                    startScan = if (!autoRefresh || isBlank) null else it.trimScan.startScan,
                    error = null
                )
            )
        }
        if (autoRefresh && !isBlank) {
            scheduleAutoKeyframeRescan(debounceMs = 280L)
        }
    }

    fun updateEndTime(value: String) {
        val curCfg = _uiState.value.activeConfig
        val autoRefresh = curCfg != null &&
            curCfg.mode == ProcessingMode.TRIM_VIDEO &&
            curCfg.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME &&
            curCfg.trimScan.panelExpanded
        val isBlank = value.trim().isEmpty()

        keyframeScanJob?.cancel()
        keyframeScanJob = null
        updateConfig {
            it.copy(
                endTimeInput = value,
                selectedEndKfSec = null,
                trimScan = it.trimScan.copy(
                    isScanning = false,
                    endScan = if (!autoRefresh || isBlank) null else it.trimScan.endScan,
                    error = null
                )
            )
        }
        if (autoRefresh && !isBlank) {
            scheduleAutoKeyframeRescan(debounceMs = 280L)
        }
    }

    fun updateTrimRangeFromSlider(
        startSec: Double,
        endSec: Double,
        updateStart: Boolean = true,
        updateEnd: Boolean = true,
        isDragFinished: Boolean = true
    ) {
        val curCfg = _uiState.value.activeConfig
        val autoRefresh = curCfg != null &&
            curCfg.mode == ProcessingMode.TRIM_VIDEO &&
            curCfg.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME &&
            curCfg.trimScan.panelExpanded

        keyframeScanJob?.cancel()
        keyframeScanJob = null
        val formattedStart = FfmpegMath.formatSliderTimestamp(startSec)
        val formattedEnd = FfmpegMath.formatSliderTimestamp(endSec)
        updateConfig { cfg ->
            val applyStart = updateStart || cfg.startTimeInput.isBlank()
            val applyEnd = updateEnd || cfg.endTimeInput.isBlank()
            val nextStartInput = if (applyStart) formattedStart else cfg.startTimeInput
            val nextEndInput = if (applyEnd) formattedEnd else cfg.endTimeInput
            val startModified = applyStart && nextStartInput != cfg.startTimeInput
            val endModified = applyEnd && nextEndInput != cfg.endTimeInput

            cfg.copy(
                startTimeInput = nextStartInput,
                endTimeInput = nextEndInput,
                selectedStartKfSec = if (applyStart) null else cfg.selectedStartKfSec,
                selectedEndKfSec = if (applyEnd) null else cfg.selectedEndKfSec,
                trimScan = if (autoRefresh) {
                    // Keep panel open & previous pills visible while dragging (zero layout jump)
                    cfg.trimScan.copy(isScanning = false, error = null)
                } else {
                    cfg.trimScan.copy(
                        isScanning = false,
                        startScan = if (startModified || applyStart) null else cfg.trimScan.startScan,
                        endScan = if (endModified || applyEnd) null else cfg.trimScan.endScan,
                        error = null
                    )
                }
            )
        }
        if (autoRefresh) {
            scheduleAutoKeyframeRescan(
                debounceMs = if (isDragFinished) 60L else 240L,
                syncInputsOnComplete = isDragFinished
            )
        }
    }

    fun selectTrimCutStrategy(strategy: TrimCutStrategy) {
        val current = _uiState.value.activeConfig ?: return
        when (strategy) {
            TrimCutStrategy.LOSSLESS_KEYFRAME -> {
                updateConfig { cfg ->
                    val nextSubMode = if (!cfg.trimSubMode.requiresLosslessKeyframes) {
                        TrimSubMode.FULL_LOSSLESS
                    } else {
                        cfg.trimSubMode
                    }
                    cfg.copy(
                        trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                        trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES,
                        trimSubMode = nextSubMode
                    )
                }
                val startStr = current.startTimeInput.trim().replace(" ", "")
                val endStr = current.endTimeInput.trim().replace(" ", "")
                val needsScan = (startStr.isNotEmpty() && current.trimScan.startScan == null) ||
                    (endStr.isNotEmpty() && current.trimScan.endScan == null)
                if (needsScan && !current.trimScan.isScanning) {
                    scanTrimKeyframes()
                }
            }
            TrimCutStrategy.PRECISE_REENCODE -> {
                keyframeScanJob?.cancel()
                keyframeScanJob = null
                updateConfig { cfg ->
                    val nextSubMode = if (cfg.trimSubMode.requiresLosslessKeyframes) {
                        TrimSubMode.CRF_AUDIO_COPY
                    } else {
                        cfg.trimSubMode
                    }
                    cfg.copy(
                        trimCutStrategy = TrimCutStrategy.PRECISE_REENCODE,
                        trimTimeChoice = TrimTimeChoice.RAW_USER_TIMES,
                        trimSubMode = nextSubMode,
                        trimScan = cfg.trimScan.copy(isScanning = false)
                    )
                }
            }
        }
    }

    fun applyNearbyKeyframeToStart(kfSec: Double) {
        keyframeScanJob?.cancel()
        keyframeScanJob = null
        val formatted = FfmpegMath.formatKeyframeTime(kfSec)
        updateConfig { current ->
            current.copy(
                startTimeInput = formatted,
                selectedStartKfSec = kfSec,
                trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES,
                trimScan = current.trimScan.copy(isScanning = false, error = null)
            )
        }
    }

    fun applyNearbyKeyframeToEnd(kfSec: Double) {
        keyframeScanJob?.cancel()
        keyframeScanJob = null
        val formatted = FfmpegMath.formatKeyframeTime(kfSec)
        updateConfig { current ->
            current.copy(
                endTimeInput = formatted,
                selectedEndKfSec = kfSec,
                trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES,
                trimScan = current.trimScan.copy(isScanning = false, error = null)
            )
        }
    }

    fun updateTrimTimeChoice(choice: TrimTimeChoice) {
        updateConfig { it.copy(trimTimeChoice = choice) }
    }

    fun updateTrimSubMode(subMode: TrimSubMode) {
        if (!subMode.requiresLosslessKeyframes) {
            keyframeScanJob?.cancel()
            keyframeScanJob = null
        }
        updateConfig { current ->
            when {
                current.trimCutStrategy == TrimCutStrategy.PRECISE_REENCODE && subMode.requiresLosslessKeyframes ->
                    current
                !subMode.requiresLosslessKeyframes ->
                    current.copy(
                        trimSubMode = subMode,
                        trimCutStrategy = TrimCutStrategy.PRECISE_REENCODE,
                        trimTimeChoice = TrimTimeChoice.RAW_USER_TIMES,
                        trimScan = current.trimScan.copy(isScanning = false)
                    )
                else ->
                    current.copy(trimSubMode = subMode)
            }
        }
    }

    fun updateAudioExtractFormat(format: AudioExtractFormat) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            val media = state.mediaInfo
            val maxKbps = if (format == AudioExtractFormat.MP3) 320 else 768
            val adjustedBitrate = if (!state.audioBitrateTouched) {
                FfmpegMath.resolveDefaultAudioBitrate(
                    baseDefaultKbps = 256,
                    sourceKbps = media?.audioBitrateKbps,
                    isLosslessSource = media?.isLosslessAudio == true,
                    maxKbps = maxKbps
                ).toString()
            } else if (format == AudioExtractFormat.MP3) {
                val parsed = FfmpegMath.parseAudioBitrateInput(cfg.audioBitrateInput)
                if (parsed != null && parsed > 320) "320" else cfg.audioBitrateInput
            } else {
                cfg.audioBitrateInput
            }
            state.copy(
                activeConfig = cfg.copy(audioExtractFormat = format, audioBitrateInput = adjustedBitrate),
                validationError = null
            )
        }
    }

    fun updateCustomCommand(command: String) {
        updateConfig { it.copy(customCommandInput = command) }
    }

    fun updatePreserveSubtitles(preserve: Boolean) {
        updateConfig { it.copy(preserveSubtitles = preserve) }
    }

    fun updateRemuxFormat(format: RemuxOutputFormat) {
        updateConfig { it.copy(remuxFormat = format) }
    }

    fun updateSubtitleEmbedMode(embedMode: SubtitleEmbedMode) {
        updateConfig { it.copy(subtitleEmbedMode = embedMode) }
    }

    fun updateSubtitleFont(font: SubtitleFontOption) {
        updateConfig { it.copy(subtitleFont = font) }
    }

    fun updateSubtitleFontSize(size: SubtitleFontSizeOption) {
        updateConfig { it.copy(subtitleFontSize = size) }
    }

    fun updateSubtitleSoftsubContainer(container: SubtitleSoftsubContainer) {
        updateConfig { it.copy(subtitleSoftsubContainer = container) }
    }

    fun loadExternalSubtitleUri(uri: Uri) {
        subtitleLoadJob?.cancel()
        subtitleLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSubtitle = true, validationError = null) }
            try {
                val loaded = FfmpegEngine.readExternalSubtitleFile(appContext, uri)
                _uiState.update { state ->
                    val cfg = state.activeConfig ?: return@update state.copy(isLoadingSubtitle = false)
                    state.copy(
                        isLoadingSubtitle = false,
                        activeConfig = cfg.copy(
                            subtitleSourceName = loaded.fileName,
                            subtitleCues = loaded.cues,
                            subtitleSourceEmbeddedIndex = null
                        ),
                        validationError = if (loaded.cues.isEmpty()) {
                            AppLanguageController.currentStrings().errNoSubtitlesLoaded
                        } else null
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val curS = AppLanguageController.currentStrings()
                _uiState.update {
                    it.copy(
                        isLoadingSubtitle = false,
                        validationError = curS.errSubtitleLoadFailed(t.message ?: curS.errUnknown)
                    )
                }
            }
        }
    }

    fun loadEmbeddedSubtitleTrack(track: SubtitleTrackInfo) {
        val media = _uiState.value.mediaInfo ?: return
        subtitleLoadJob?.cancel()
        subtitleLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSubtitle = true, validationError = null) }
            try {
                val cues = FfmpegEngine.extractEmbeddedSubtitleCues(
                    context = appContext,
                    uri = media.uri,
                    subtitleStreamIndex = track.subtitleStreamIndex
                )
                val label = buildString {
                    append(track.formatTag)
                    if (!track.language.isNullOrBlank()) append(" (${track.language.uppercase()})")
                    else if (!track.title.isNullOrBlank()) append(" (${track.title.take(14)})")
                    else append(" #${track.subtitleStreamIndex + 1}")
                }
                _uiState.update { state ->
                    val cfg = state.activeConfig ?: return@update state.copy(isLoadingSubtitle = false)
                    state.copy(
                        isLoadingSubtitle = false,
                        activeConfig = cfg.copy(
                            subtitleSourceName = label,
                            subtitleCues = cues,
                            subtitleSourceEmbeddedIndex = track.subtitleStreamIndex
                        ),
                        validationError = if (cues.isEmpty()) {
                            AppLanguageController.currentStrings().errNoSubtitlesLoaded
                        } else null
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val curS = AppLanguageController.currentStrings()
                _uiState.update {
                    it.copy(
                        isLoadingSubtitle = false,
                        validationError = curS.errSubtitleLoadFailed(t.message ?: curS.errUnknown)
                    )
                }
            }
        }
    }

    fun updateSubtitleCue(
        cueId: Long,
        newText: String,
        newStartSec: Double? = null,
        newEndSec: Double? = null
    ) {
        updateConfig { cfg ->
            val updated = cfg.subtitleCues.map { cue ->
                if (cue.id == cueId) {
                    val s = (newStartSec ?: cue.startSec).coerceAtLeast(0.0)
                    val e = maxOf(newEndSec ?: cue.endSec, s + 0.1)
                    cue.copy(startSec = s, endSec = e, text = newText)
                } else {
                    cue
                }
            }
            val finalList = if (newStartSec != null || newEndSec != null) {
                updated.sortedWith(compareBy<SubtitleCue> { it.startSec }.thenBy { it.endSec })
            } else {
                updated
            }
            cfg.copy(subtitleCues = finalList)
        }
    }

    fun addSubtitleCueAt(timeSec: Double): Long {
        val mediaDur = _uiState.value.mediaInfo?.durationSec
        val safeStart = if (mediaDur != null && mediaDur > 0.3) {
            timeSec.coerceIn(0.0, (mediaDur - 0.3).coerceAtLeast(0.0))
        } else {
            timeSec.coerceAtLeast(0.0)
        }
        val safeEnd = if (mediaDur != null && mediaDur > safeStart + 0.2) {
            (safeStart + 2.5).coerceAtMost(mediaDur)
        } else {
            safeStart + 2.5
        }
        val nextId = ((_uiState.value.activeConfig?.subtitleCues?.maxOfOrNull { it.id } ?: 0L) + 1L)
        val defaultText = if (AppLanguageController.currentLanguage() == AppLanguage.TR) {
            "Yeni altyazı"
        } else {
            "New subtitle"
        }
        updateConfig { cfg ->
            val nextList = (cfg.subtitleCues + SubtitleCue(
                id = nextId,
                startSec = safeStart,
                endSec = maxOf(safeEnd, safeStart + 0.2),
                text = defaultText
            )).sortedWith(compareBy<SubtitleCue> { it.startSec }.thenBy { it.endSec })
            cfg.copy(
                subtitleSourceName = cfg.subtitleSourceName.ifBlank { "manual.srt" },
                subtitleCues = nextList
            )
        }
        return nextId
    }

    fun deleteSubtitleCue(cueId: Long) {
        updateConfig { cfg ->
            cfg.copy(subtitleCues = cfg.subtitleCues.filterNot { it.id == cueId })
        }
    }

    fun scanTrimKeyframes() {
        val s = AppLanguageController.currentStrings()
        val media = _uiState.value.mediaInfo ?: return
        val cfg = _uiState.value.activeConfig ?: return
        val startStr = cfg.startTimeInput.trim().replace(" ", "").replace(',', '.')
        val endStr = cfg.endTimeInput.trim().replace(" ", "").replace(',', '.')

        if (startStr.isEmpty() && endStr.isEmpty()) {
            _uiState.update { it.copy(validationError = s.errEnterTrimTimeFirst) }
            return
        }

        val startSec = if (startStr.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(startStr) else null
        val endSec = if (endStr.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(endStr) else null

        if (startStr.isNotEmpty() && startSec == null) {
            _uiState.update { it.copy(validationError = s.errInvalidStartTime) }
            return
        }
        if (endStr.isNotEmpty() && endSec == null) {
            _uiState.update { it.copy(validationError = s.errInvalidEndTime) }
            return
        }
        if (startSec != null && endSec != null && endSec <= startSec) {
            _uiState.update { it.copy(validationError = s.errEndBeforeStart) }
            return
        }
        if (media.durationSec != null && startSec != null && startSec >= media.durationSec - 0.05) {
            _uiState.update {
                it.copy(
                    validationError = s.errStartExceedsDuration(
                        FfmpegMath.formatKeyframeTime(media.durationSec, s)
                    )
                )
            }
            return
        }
        if (media.durationSec != null && endSec != null && endSec > media.durationSec + 0.05) {
            _uiState.update {
                it.copy(
                    validationError = s.trimEndExceedsDurationWarning(
                        FfmpegMath.formatKeyframeTime(media.durationSec, s)
                    )
                )
            }
            return
        }

        if (!media.hasVideo) {
            _uiState.update { it.copy(validationError = s.errKeyframeRequiresVideo) }
            return
        }

        keyframeScanJob?.cancel()
        keyframeScanJob = viewModelScope.launch {
            updateConfig {
                it.copy(
                    trimScan = it.trimScan.copy(
                        panelExpanded = true,
                        isScanning = true,
                        error = null
                    )
                )
            }
            try {
                val startDeferred = startSec?.let { st ->
                    async {
                        FfmpegEngine.scanKeyframes(
                            context = appContext,
                            uri = media.uri,
                            targetSec = st,
                            isStart = true,
                            durationSec = media.durationSec
                        )
                    }
                }
                val endDeferred = endSec?.let { en ->
                    async {
                        FfmpegEngine.scanKeyframes(
                            context = appContext,
                            uri = media.uri,
                            targetSec = en,
                            isStart = false,
                            durationSec = media.durationSec
                        )
                    }
                }
                val startResult = startDeferred?.await()
                val endResult = endDeferred?.await()

                updateConfig {
                    val pickedStartKf = startResult?.prevKf
                    val rawEndKf = endResult?.nextKf
                    val pickedEndKf = if (rawEndKf != null && pickedStartKf != null && rawEndKf <= pickedStartKf + 0.05) {
                        endResult.nearbyKeyframes.firstOrNull { kf -> kf > pickedStartKf + 0.05 } ?: rawEndKf
                    } else {
                        rawEndKf
                    }
                    it.copy(
                        startTimeInput = pickedStartKf?.let { kf -> FfmpegMath.formatKeyframeTime(kf) } ?: it.startTimeInput,
                        endTimeInput = pickedEndKf?.let { kf -> FfmpegMath.formatKeyframeTime(kf) } ?: it.endTimeInput,
                        trimScan = TrimScanState(
                            panelExpanded = true,
                            isScanning = false,
                            startScan = startResult,
                            endScan = endResult,
                            error = null
                        ),
                        selectedStartKfSec = pickedStartKf,
                        selectedEndKfSec = pickedEndKf,
                        trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                        trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val curS = AppLanguageController.currentStrings()
                updateConfig {
                    it.copy(
                        trimScan = it.trimScan.copy(
                            panelExpanded = true,
                            isScanning = false,
                            error = curS.errKeyframeScanFailed(t.message ?: curS.errUnknown)
                        )
                    )
                }
            }
        }
    }

    /**
     * Silently re-scans whichever trim endpoint(s) changed once the keyframe picker is open,
     * preserving the untouched endpoint and avoiding validation-error flicker mid-typing.
     * When [syncInputsOnComplete] is true (slider release), also aligns the changed endpoint's
     * text input with the newly pre-selected safe keyframe chip.
     */
    private fun scheduleAutoKeyframeRescan(
        debounceMs: Long,
        syncInputsOnComplete: Boolean = false
    ) {
        keyframeScanJob?.cancel()
        keyframeScanJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            val media = _uiState.value.mediaInfo ?: return@launch
            val cfg = _uiState.value.activeConfig ?: return@launch
            if (!media.hasVideo ||
                cfg.mode != ProcessingMode.TRIM_VIDEO ||
                cfg.trimCutStrategy != TrimCutStrategy.LOSSLESS_KEYFRAME ||
                !cfg.trimScan.panelExpanded
            ) {
                return@launch
            }

            val startRaw = cfg.startTimeInput.trim().replace(" ", "").replace(',', '.')
            val endRaw = cfg.endTimeInput.trim().replace(" ", "").replace(',', '.')
            val parsedStart = if (startRaw.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(startRaw) else null
            val parsedEnd = if (endRaw.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(endRaw) else null

            val validStartSec = parsedStart?.takeIf { st ->
                media.durationSec == null || st < media.durationSec - 0.05
            }
            val validEndSec = parsedEnd?.takeIf { en ->
                media.durationSec == null || en <= media.durationSec + 0.05
            }

            val prevStartTarget = cfg.trimScan.startScan?.targetSec
            val prevEndTarget = cfg.trimScan.endScan?.targetSec
            val needStartScan = validStartSec != null &&
                cfg.selectedStartKfSec == null &&
                (prevStartTarget == null || abs(prevStartTarget - validStartSec) > 0.02)
            val needEndScan = validEndSec != null &&
                cfg.selectedEndKfSec == null &&
                (prevEndTarget == null || abs(prevEndTarget - validEndSec) > 0.02)

            if (!needStartScan && !needEndScan) return@launch

            updateConfig { it.copy(trimScan = it.trimScan.copy(isScanning = true, error = null)) }
            try {
                val startDeferred = if (needStartScan) {
                    async {
                        FfmpegEngine.scanKeyframes(
                            context = appContext,
                            uri = media.uri,
                            targetSec = validStartSec,
                            isStart = true,
                            durationSec = media.durationSec
                        )
                    }
                } else null
                val endDeferred = if (needEndScan) {
                    async {
                        FfmpegEngine.scanKeyframes(
                            context = appContext,
                            uri = media.uri,
                            targetSec = validEndSec,
                            isStart = false,
                            durationSec = media.durationSec
                        )
                    }
                } else null

                val newStartScan = startDeferred?.await()
                val newEndScan = endDeferred?.await()

                updateConfig { latest ->
                    val latestStartRaw = latest.startTimeInput.trim().replace(" ", "").replace(',', '.')
                    val latestEndRaw = latest.endTimeInput.trim().replace(" ", "").replace(',', '.')
                    val startStillMatches = needStartScan && latestStartRaw == startRaw
                    val endStillMatches = needEndScan && latestEndRaw == endRaw

                    val mergedStartScan = if (startStillMatches) newStartScan else latest.trimScan.startScan
                    val mergedEndScan = if (endStillMatches) newEndScan else latest.trimScan.endScan
                    val mergedStartKf = if (startStillMatches) newStartScan?.prevKf else latest.selectedStartKfSec
                    val rawMergedEndKf = if (endStillMatches) newEndScan?.nextKf else latest.selectedEndKfSec
                    val mergedEndKf = if (endStillMatches && rawMergedEndKf != null && mergedStartKf != null && rawMergedEndKf <= mergedStartKf + 0.05) {
                        newEndScan?.nearbyKeyframes?.firstOrNull { kf -> kf > mergedStartKf + 0.05 } ?: rawMergedEndKf
                    } else {
                        rawMergedEndKf
                    }

                    val nextStartInput = if (syncInputsOnComplete && startStillMatches && mergedStartKf != null) {
                        FfmpegMath.formatKeyframeTime(mergedStartKf)
                    } else {
                        latest.startTimeInput
                    }
                    val nextEndInput = if (syncInputsOnComplete && endStillMatches && mergedEndKf != null) {
                        FfmpegMath.formatKeyframeTime(mergedEndKf)
                    } else {
                        latest.endTimeInput
                    }

                    latest.copy(
                        startTimeInput = nextStartInput,
                        endTimeInput = nextEndInput,
                        trimScan = latest.trimScan.copy(
                            panelExpanded = true,
                            isScanning = false,
                            startScan = mergedStartScan,
                            endScan = mergedEndScan,
                            error = null
                        ),
                        selectedStartKfSec = mergedStartKf,
                        selectedEndKfSec = mergedEndKf
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                updateConfig { it.copy(trimScan = it.trimScan.copy(isScanning = false)) }
            }
        }
    }

    /**
     * Validates the current mode configuration synchronously before permission dialogs or
     * foreground service launch, updating [WorkbenchUiState.validationError] on failure.
     */
    fun validateBeforeLaunch(): Boolean {
        if (EncodingJobController.state.value is EncodingState.Running) return false
        val s = AppLanguageController.currentStrings()
        val media = _uiState.value.mediaInfo ?: return false
        val cfg = _uiState.value.activeConfig ?: return false

        when (cfg.mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> {
                if (cfg.mode == ProcessingMode.CUSTOM_SIZE && FfmpegMath.parsePositiveDouble(cfg.targetMbInput) == null) {
                    _uiState.update { it.copy(validationError = s.errInvalidTargetMb) }
                    return false
                }
                if (media.durationSec == null && FfmpegMath.parsePositiveDouble(cfg.manualDurationInput) == null) {
                    _uiState.update { it.copy(validationError = s.errEnterManualDuration) }
                    return false
                }
            }
            ProcessingMode.TRIM_VIDEO -> {
                val sRaw = cfg.startTimeInput.trim().replace(" ", "").replace(',', '.')
                val eRaw = cfg.endTimeInput.trim().replace(" ", "").replace(',', '.')
                if (sRaw.isEmpty() && eRaw.isEmpty()) {
                    _uiState.update { it.copy(validationError = s.errNoTrimRangeEntered) }
                    return false
                }
                val sSec = if (sRaw.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(sRaw) else null
                val eSec = if (eRaw.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(eRaw) else null
                if ((sRaw.isNotEmpty() && sSec == null) || (eRaw.isNotEmpty() && eSec == null)) {
                    _uiState.update { it.copy(validationError = s.errInvalidTimeFormat) }
                    return false
                }
                val (effStartStr, effEndStr, _) = FfmpegMath.resolveTrimRange(cfg, media.durationSec)
                val effStart = effStartStr?.let { FfmpegMath.parseTimestampToSeconds(it) } ?: sSec
                val effEnd = effEndStr?.let { FfmpegMath.parseTimestampToSeconds(it) } ?: eSec

                if ((sSec != null && eSec != null && eSec <= sSec) ||
                    (effStart != null && effEnd != null && effEnd <= effStart)
                ) {
                    _uiState.update { it.copy(validationError = s.errEndBeforeStart) }
                    return false
                }
                val startToCheck = effStart ?: sSec
                if (media.durationSec != null && startToCheck != null && startToCheck >= media.durationSec - 0.05) {
                    _uiState.update {
                        it.copy(
                            validationError = s.errStartExceedsDuration(
                                FfmpegMath.formatKeyframeTime(media.durationSec, s)
                            )
                        )
                    }
                    return false
                }
                if (media.durationSec != null && eSec != null && eSec > media.durationSec + 0.05) {
                    _uiState.update {
                        it.copy(
                            validationError = s.trimEndExceedsDurationWarning(
                                FfmpegMath.formatKeyframeTime(media.durationSec, s)
                            )
                        )
                    }
                    return false
                }
            }
            ProcessingMode.AUDIO_ONLY_AAC,
            ProcessingMode.EXTRACT_AUDIO -> {
                if (!media.hasAudio) {
                    _uiState.update { it.copy(validationError = s.errNoAudioTrack) }
                    return false
                }
            }
            ProcessingMode.EMBED_SUBTITLES -> {
                if (cfg.subtitleCues.none { it.text.isNotBlank() }) {
                    _uiState.update { it.copy(validationError = s.errNoSubtitlesLoaded) }
                    return false
                }
            }
            ProcessingMode.CUSTOM_COMMAND -> {
                if (cfg.customCommandInput.isBlank()) {
                    _uiState.update { it.copy(validationError = s.errEnterCustomCommand) }
                    return false
                }
                val parsed = com.example.domain.CustomFfmpegParser.buildPlan(
                    rawCommand = cfg.customCommandInput,
                    media = media,
                    workDirPath = "",
                    defaultOutputFileName = s.customCmdDefaultOutput,
                    inputTokenProvider = { "\$IN" },
                    isPreview = true
                )
                if (parsed.steps.isEmpty()) {
                    _uiState.update { it.copy(validationError = s.customCmdNoValidCommandWarning) }
                    return false
                }
            }
            else -> Unit
        }

        _uiState.update { it.copy(validationError = null) }
        return true
    }

    fun startEncoding() {
        if (!validateBeforeLaunch()) return
        val media = _uiState.value.mediaInfo ?: return
        val cfg = _uiState.value.activeConfig ?: return
        val continuation = _uiState.value.pendingTurboContinuation
        if (continuation != null &&
            (cfg.mode == ProcessingMode.VIDEO_ONLY_CRF || cfg.mode == ProcessingMode.VIDEO_AND_AUDIO_CRF)
        ) {
            _uiState.update { it.copy(pendingTurboContinuation = null) }
            EncodingForegroundService.startVideoAudioPrepThenTurbo(
                context = appContext,
                media = media,
                prepConfig = cfg,
                turboTargetConfig = continuation.targetConfig
            )
            return
        }
        EncodingForegroundService.startEncoding(appContext, media, cfg)
    }

    fun switchCurrentJobToTurbo() {
        val cur = EncodingJobController.state.value
        if (cur !is EncodingState.Running || cur.isHardwareTurbo) return
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        EncodingForegroundService.startTurboEncoding(appContext, media, cfg)
    }

    fun tryRemuxThenRetryTurbo() {
        val cur = EncodingJobController.state.value
        if (cur !is EncodingState.Failed || !cur.isHardwareTurbo || cur.hasTriedRemux) return
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        EncodingForegroundService.startRemuxThenTurbo(appContext, media, cfg)
    }

    fun tryAudioConvertThenRetryTurbo(audioBitrateKbps: Int) {
        val cur = EncodingJobController.state.value
        if (cur !is EncodingState.Failed || !cur.isHardwareTurbo || cur.hasTriedAudioConvert) return
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        EncodingForegroundService.startAudioConvertThenTurbo(
            context = appContext,
            media = media,
            config = cfg,
            audioBitrateKbps = audioBitrateKbps
        )
    }

    fun openVideoOnlyPrepForTurbo() {
        openVideoPrepForTurbo(includeAudioEncode = false)
    }

    fun openVideoAudioPrepForTurbo() {
        openVideoPrepForTurbo(includeAudioEncode = true)
    }

    private fun openVideoPrepForTurbo(includeAudioEncode: Boolean) {
        val cur = EncodingJobController.state.value
        if (cur !is EncodingState.Failed || !cur.isHardwareTurbo) return
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val targetCfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        val caps = _uiState.value.capabilities

        val wants10BitTarget = targetCfg.codec == VideoCodecOption.H265 &&
            targetCfg.bitDepth == BitDepthOption.BIT_10 &&
            caps.x265Supports10Bit
        val prepCodec = if (wants10BitTarget) VideoCodecOption.H265 else VideoCodecOption.H264
        val prepBitDepth = if (wants10BitTarget) BitDepthOption.BIT_10 else BitDepthOption.BIT_8
        val recommendedAudio = Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media)

        val prepConfig = ModeConfigState(
            mode = if (includeAudioEncode) ProcessingMode.VIDEO_AND_AUDIO_CRF else ProcessingMode.VIDEO_ONLY_CRF,
            codec = prepCodec,
            bitDepth = prepBitDepth,
            fps = FpsOption.ORIGINAL,
            resolution = ResolutionOption.ORIGINAL,
            preset = PresetOption.FAST,
            audioBitrateInput = recommendedAudio.toString()
        )

        // Update activeConfig & pendingTurboContinuation before resetting EncodingJobController
        // so Compose slides cleanly from Turbo Failed -> Config without flashing Home.
        _uiState.update {
            it.copy(
                mediaInfo = media,
                activeConfig = prepConfig,
                pendingTurboContinuation = PendingTurboContinuation(
                    sourceMedia = media,
                    targetConfig = targetCfg
                ),
                validationError = null,
                audioBitrateTouched = false,
                fpsTouched = false,
                resolutionTouched = false,
                probeError = null
            )
        }
        EncodingJobController.forceResetToIdle()
    }

    fun fallbackFromTurboToFfmpeg(useFastPreset: Boolean = false) {
        val cur = EncodingJobController.state.value
        if (cur !is EncodingState.Failed || !cur.isHardwareTurbo) return
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        val targetCfg = if (useFastPreset) cfg.copy(preset = PresetOption.FAST) else cfg
        _uiState.update {
            it.copy(
                activeConfig = targetCfg,
                pendingTurboContinuation = null,
                validationError = null
            )
        }
        EncodingForegroundService.startEncoding(appContext, media, targetCfg)
    }

    fun cancelEncoding() {
        keyframeScanJob?.cancel()
        keyframeScanJob = null
        // Return cleanly to the Home screen (Ana Menü) right away
        _uiState.update {
            it.copy(
                activeConfig = null,
                pendingTurboContinuation = null,
                validationError = null
            )
        }
        // Single atomic cancel: invalidates run ID, cancels native FFmpeg/Media3, and stops ForegroundService
        EncodingForegroundService.cancelEncoding(appContext)
    }

    fun dismissEncodingResult(returnToWorkbench: Boolean = true) {
        EncodingJobController.forceResetToIdle()
        if (returnToWorkbench) {
            _uiState.update {
                it.copy(
                    activeConfig = null,
                    pendingTurboContinuation = null,
                    validationError = null
                )
            }
        }
    }

    /** Loads a completed output file directly back into the Home screen as the selected source media. */
    fun editCompletedOutput(output: CompletedOutput) {
        val uri = runCatching { Uri.parse(output.contentUriString) }.getOrNull() ?: return
        onMediaSelected(uri)
    }

    fun setShowHistorySheet(show: Boolean) {
        _uiState.update { it.copy(showHistorySheet = show) }
    }

    fun clearHistory() {
        viewModelScope.launch { prefsRepo.clearHistory() }
    }

    fun clearProbeError() {
        probeErrorAutoClearJob?.cancel()
        probeErrorAutoClearJob = null
        _uiState.update { it.copy(probeError = null) }
    }

    private fun showProbeError(message: String) {
        probeErrorAutoClearJob?.cancel()
        _uiState.update { it.copy(probeError = message) }
        probeErrorAutoClearJob = viewModelScope.launch {
            delay(5_000L)
            _uiState.update { state ->
                if (state.probeError == message) state.copy(probeError = null) else state
            }
        }
    }

    private fun updateConfig(transform: (ModeConfigState) -> ModeConfigState) {
        _uiState.update { state ->
            val current = state.activeConfig ?: return@update state
            state.copy(activeConfig = transform(current), validationError = null)
        }
    }
}
