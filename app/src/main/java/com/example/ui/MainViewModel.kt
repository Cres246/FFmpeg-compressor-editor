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
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.PresetOption
import com.example.domain.ProcessingMode
import com.example.domain.ResolutionOption
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

data class WorkbenchUiState(
    val mediaInfo: MediaInfo? = null,
    val isProbing: Boolean = false,
    val probeError: String? = null,
    val capabilities: EncoderCapabilities = EncoderCapabilities(),
    val activeConfig: ModeConfigState? = null,
    val validationError: String? = null,
    val audioBitrateTouched: Boolean = false,
    val fpsTouched: Boolean = false,
    val resolutionTouched: Boolean = false,
    val showHistorySheet: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val prefsRepo = PreferencesRepository(appContext)

    private val _uiState = MutableStateFlow(WorkbenchUiState())
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()
    private var probeErrorAutoClearJob: Job? = null

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
        viewModelScope.launch {
            FfmpegEngine.cleanupOrphanTempDirs(appContext)
            val caps = FfmpegEngine.probeEncoderCapabilities()
            _uiState.update { it.copy(capabilities = caps) }
        }
        viewModelScope.launch {
            var lastGen = EncodingJobController.cancelGeneration.value
            EncodingJobController.cancelGeneration.collect { gen ->
                if (gen != lastGen) {
                    lastGen = gen
                    _uiState.update { it.copy(activeConfig = null, validationError = null) }
                }
            }
        }
    }

    fun setLanguagePreference(pref: LanguagePreference) {
        AppLanguageController.setPreference(appContext, pref)
        _uiState.update { it.copy(probeError = null, validationError = null) }
    }

    fun onMediaSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProbing = true,
                    probeError = null,
                    activeConfig = null,
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
        val defaultPreset = when (mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> PresetOption.MEDIUM
            else -> PresetOption.FAST
        }
        val defaultFps = FpsOption.ORIGINAL
        val defaultAudioKbps = when (mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> "128"
            ProcessingMode.TRIM_VIDEO -> "192"
            else -> "256"
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
            customCommandInput = ""
        )

        _uiState.update {
            it.copy(
                activeConfig = initialConfig,
                validationError = null,
                audioBitrateTouched = false,
                fpsTouched = false,
                resolutionTouched = false,
                probeError = null
            )
        }
    }

    fun closeConfig() {
        _uiState.update { it.copy(activeConfig = null, validationError = null) }
    }

    fun updateTargetMb(value: String) {
        _uiState.update { state ->
            val cfg = state.activeConfig ?: return@update state
            val media = state.mediaInfo
            val parsedMb = FfmpegMath.parsePositiveDouble(value)
            val newAudio = if (!state.audioBitrateTouched && parsedMb != null) {
                FfmpegMath.defaultAudioBitrateForTwoPass(parsedMb).toString()
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
        updateConfig {
            it.copy(
                startTimeInput = value,
                selectedStartKfSec = null,
                trimScan = it.trimScan.copy(startScan = null, error = null)
            )
        }
    }

    fun updateEndTime(value: String) {
        updateConfig {
            it.copy(
                endTimeInput = value,
                selectedEndKfSec = null,
                trimScan = it.trimScan.copy(endScan = null, error = null)
            )
        }
    }

    fun updateTrimRangeFromSlider(startSec: Double, endSec: Double) {
        val formattedStart = FfmpegMath.formatSliderTimestamp(startSec)
        val formattedEnd = FfmpegMath.formatSliderTimestamp(endSec)
        updateConfig {
            it.copy(
                startTimeInput = formattedStart,
                endTimeInput = formattedEnd,
                selectedStartKfSec = null,
                selectedEndKfSec = null,
                trimScan = TrimScanState()
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
                updateConfig { cfg ->
                    val nextSubMode = if (cfg.trimSubMode.requiresLosslessKeyframes) {
                        TrimSubMode.CRF_AUDIO_COPY
                    } else {
                        cfg.trimSubMode
                    }
                    cfg.copy(
                        trimCutStrategy = TrimCutStrategy.PRECISE_REENCODE,
                        trimTimeChoice = TrimTimeChoice.RAW_USER_TIMES,
                        trimSubMode = nextSubMode
                    )
                }
            }
        }
    }

    fun applyNearbyKeyframeToStart(kfSec: Double) {
        updateConfig { current ->
            current.copy(
                selectedStartKfSec = kfSec,
                trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES
            )
        }
    }

    fun applyNearbyKeyframeToEnd(kfSec: Double) {
        updateConfig { current ->
            current.copy(
                selectedEndKfSec = kfSec,
                trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES
            )
        }
    }

    fun updateTrimTimeChoice(choice: TrimTimeChoice) {
        updateConfig { it.copy(trimTimeChoice = choice) }
    }

    fun updateTrimSubMode(subMode: TrimSubMode) {
        updateConfig { current ->
            if (current.trimCutStrategy == TrimCutStrategy.PRECISE_REENCODE && subMode.requiresLosslessKeyframes) {
                current
            } else {
                current.copy(trimSubMode = subMode)
            }
        }
    }

    fun updateAudioExtractFormat(format: AudioExtractFormat) {
        updateConfig { cfg ->
            val adjustedBitrate = if (format == AudioExtractFormat.MP3) {
                val parsed = cfg.audioBitrateInput.trim().replace("k", "", ignoreCase = true).toIntOrNull()
                if (parsed != null && parsed > 320) "320" else cfg.audioBitrateInput
            } else {
                cfg.audioBitrateInput
            }
            cfg.copy(audioExtractFormat = format, audioBitrateInput = adjustedBitrate)
        }
    }

    fun updateCustomCommand(command: String) {
        updateConfig { it.copy(customCommandInput = command) }
    }

    fun scanTrimKeyframes() {
        val s = AppLanguageController.currentStrings()
        val media = _uiState.value.mediaInfo ?: return
        val cfg = _uiState.value.activeConfig ?: return
        val startStr = cfg.startTimeInput.trim().replace(" ", "")
        val endStr = cfg.endTimeInput.trim().replace(" ", "")

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

        viewModelScope.launch {
            updateConfig { it.copy(trimScan = TrimScanState(isScanning = true)) }
            try {
                val startDeferred = startSec?.let { st ->
                    async { FfmpegEngine.scanKeyframes(appContext, media.uri, st, isStart = true) }
                }
                val endDeferred = endSec?.let { en ->
                    async { FfmpegEngine.scanKeyframes(appContext, media.uri, en, isStart = false) }
                }
                val startResult = startDeferred?.await()
                val endResult = endDeferred?.await()

                updateConfig {
                    it.copy(
                        trimScan = TrimScanState(
                            isScanning = false,
                            startScan = startResult,
                            endScan = endResult,
                            error = null
                        ),
                        selectedStartKfSec = startResult?.prevKf,
                        selectedEndKfSec = endResult?.nextKf,
                        trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                        trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES
                    )
                }
            } catch (t: Throwable) {
                val curS = AppLanguageController.currentStrings()
                updateConfig {
                    it.copy(
                        trimScan = TrimScanState(
                            isScanning = false,
                            error = curS.errKeyframeScanFailed(t.message ?: curS.errUnknown)
                        )
                    )
                }
            }
        }
    }

    fun startEncoding() {
        val s = AppLanguageController.currentStrings()
        val media = _uiState.value.mediaInfo ?: return
        val cfg = _uiState.value.activeConfig ?: return

        // Validate mode-specific constraints cleanly
        when (cfg.mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> {
                if (cfg.mode == ProcessingMode.CUSTOM_SIZE && FfmpegMath.parsePositiveDouble(cfg.targetMbInput) == null) {
                    _uiState.update { it.copy(validationError = s.errInvalidTargetMb) }
                    return
                }
                if (media.durationSec == null && FfmpegMath.parsePositiveDouble(cfg.manualDurationInput) == null) {
                    _uiState.update { it.copy(validationError = s.errEnterManualDuration) }
                    return
                }
            }
            ProcessingMode.TRIM_VIDEO -> {
                val sRaw = cfg.startTimeInput.trim().replace(" ", "")
                val eRaw = cfg.endTimeInput.trim().replace(" ", "")
                if (sRaw.isEmpty() && eRaw.isEmpty()) {
                    _uiState.update { it.copy(validationError = s.errNoTrimRangeEntered) }
                    return
                }
                val sSec = if (sRaw.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(sRaw) else null
                val eSec = if (eRaw.isNotEmpty()) FfmpegMath.parseTimestampToSeconds(eRaw) else null
                if ((sRaw.isNotEmpty() && sSec == null) || (eRaw.isNotEmpty() && eSec == null)) {
                    _uiState.update { it.copy(validationError = s.errInvalidTimeFormat) }
                    return
                }
                if (sSec != null && eSec != null && eSec <= sSec) {
                    _uiState.update { it.copy(validationError = s.errEndBeforeStart) }
                    return
                }
                if (media.durationSec != null && eSec != null && eSec > media.durationSec + 0.05) {
                    _uiState.update {
                        it.copy(
                            validationError = s.trimEndExceedsDurationWarning(
                                FfmpegMath.formatKeyframeTime(media.durationSec, s)
                            )
                        )
                    }
                    return
                }
            }
            ProcessingMode.AUDIO_ONLY_AAC,
            ProcessingMode.EXTRACT_AUDIO -> {
                if (!media.hasAudio) {
                    _uiState.update { it.copy(validationError = s.errNoAudioTrack) }
                    return
                }
            }
            ProcessingMode.CUSTOM_COMMAND -> {
                if (cfg.customCommandInput.isBlank()) {
                    _uiState.update { it.copy(validationError = s.errEnterCustomCommand) }
                    return
                }
            }
            else -> Unit
        }

        _uiState.update { it.copy(validationError = null) }
        EncodingForegroundService.startEncoding(appContext, media, cfg)
    }

    fun switchCurrentJobToTurbo() {
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        EncodingForegroundService.startTurboEncoding(appContext, media, cfg)
    }

    fun tryRemuxThenRetryTurbo() {
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        EncodingForegroundService.startRemuxThenTurbo(appContext, media, cfg)
    }

    fun fallbackFromTurboToFfmpeg(useFastPreset: Boolean = false) {
        val snapshot = EncodingJobController.activeJobSnapshot
        val media = snapshot?.media ?: _uiState.value.mediaInfo ?: return
        val cfg = snapshot?.config ?: _uiState.value.activeConfig ?: return
        val targetCfg = if (useFastPreset) cfg.copy(preset = PresetOption.FAST) else cfg
        EncodingForegroundService.startEncoding(appContext, media, targetCfg)
    }

    fun cancelEncoding() {
        // Immediately invalidate active run ID, cancel native FFmpeg / Media3, and reset state to Idle
        EncodingJobController.cancelActiveRun()
        // Return cleanly to the Home screen (Ana Menü) right away
        _uiState.update { it.copy(activeConfig = null, validationError = null) }
        // Instruct the foreground service to release WakeLock/ADPF boost, clean temp files, and stop
        EncodingForegroundService.cancelEncoding(appContext)
    }

    fun dismissEncodingResult(returnToWorkbench: Boolean = true) {
        EncodingJobController.forceResetToIdle()
        if (returnToWorkbench) {
            _uiState.update { it.copy(activeConfig = null, validationError = null) }
        }
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
