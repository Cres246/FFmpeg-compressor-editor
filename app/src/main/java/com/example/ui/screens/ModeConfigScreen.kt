package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.AudioExtractFormat
import com.example.domain.CustomCommandPlan
import com.example.domain.CustomFfmpegParser
import com.example.domain.EncoderCapabilities
import com.example.domain.FfmpegMath
import com.example.domain.LocalAppStrings
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.PendingTurboContinuation
import com.example.domain.PresetOption
import com.example.domain.ProcessingMode
import com.example.domain.TrimSubMode
import com.example.domain.localizedLabel
import com.example.domain.localizedTitle
import com.example.ui.MainViewModel
import com.example.ui.components.AudioBitrateSection
import com.example.ui.components.CodecAndBitDepthSection
import com.example.ui.components.ConfigSectionCard
import com.example.ui.components.FpsAndResolutionSection
import com.example.ui.components.OutputAndCommandPreviewCard
import com.example.ui.components.PresetSection
import com.example.ui.components.TrimScrubState
import com.example.ui.components.TrimSubModeSelector
import com.example.ui.components.TrimTimeAndKeyframeSection
import com.example.ui.components.TrimVideoPreviewCard
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoLabelStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModeConfigScreen(
    media: MediaInfo,
    config: ModeConfigState,
    capabilities: EncoderCapabilities,
    validationError: String?,
    viewModel: MainViewModel,
    pendingTurboContinuation: PendingTurboContinuation? = null,
    isActiveStage: Boolean = true,
    onPlaySourceMedia: () -> Unit = {},
    onStartClick: () -> Unit
) {
    BackHandler(enabled = isActiveStage) { viewModel.closeConfig() }

    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val trimScrubState = remember(media.uri) { TrimScrubState() }
    val customCommandPreviewPlan = remember(media, config.mode, config.customCommandInput, strings) {
        if (config.mode == ProcessingMode.CUSTOM_COMMAND) {
            CustomFfmpegParser.buildPlan(
                rawCommand = config.customCommandInput,
                media = media,
                workDirPath = "",
                defaultOutputFileName = strings.customCmdDefaultOutput,
                inputTokenProvider = { "\$IN" },
                isPreview = true
            )
        } else {
            null
        }
    }
    val outputFileName = remember(media, config, strings, customCommandPreviewPlan) {
        if (config.mode == ProcessingMode.CUSTOM_COMMAND) {
            customCommandPreviewPlan?.primaryFinalOutputName ?: strings.customCommandOutputPlaceholder
        } else {
            FfmpegMath.computeOutputFileName(media, config, strings)
        }
    }
    val commandPreview = remember(media, config, strings, customCommandPreviewPlan) {
        if (config.mode == ProcessingMode.CUSTOM_COMMAND) {
            customCommandPreviewPlan?.let { CustomFfmpegParser.formatPreview(it) }
                ?.ifBlank { config.customCommandInput.trim() }
                .orEmpty()
        } else {
            val steps = FfmpegMath.buildFfmpegSteps(
                media = media,
                config = config,
                inputTokenProvider = { "\$IN" },
                outputPath = outputFileName,
                passLogPrefix = "\$PASSDIR/ff2pass",
                strings = strings
            )
            FfmpegMath.formatCommandPreview(steps)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${config.mode.formattedNumber} · ${config.mode.localizedTitle(strings)}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = media.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.closeConfig() },
                        modifier = Modifier.testTag("config_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = strings.actionBack
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, extColors.subtleBorder),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    AnimatedVisibility(
                        visible = !validationError.isNullOrBlank(),
                        enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
                    ) {
                        Row(
                            modifier = Modifier.padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = validationError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Button(
                        onClick = onStartClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_encoding_button"),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.actionStart,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pendingTurboContinuation != null && config.mode == ProcessingMode.VIDEO_AND_AUDIO_CRF) {
                item {
                    val targetTitle = pendingTurboContinuation.targetConfig.mode.localizedTitle(strings)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("turbo_prep_continuation_banner"),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = strings.turboVideoAudioPrepBannerTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = strings.turboVideoAudioPrepBannerBody(targetTitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                                )
                            }
                        }
                    }
                }
            }
            when (config.mode) {
                ProcessingMode.DISCORD_20MB,
                ProcessingMode.WHATSAPP_100MB,
                ProcessingMode.GMAIL_25MB,
                ProcessingMode.CUSTOM_SIZE -> {
                    item {
                        TwoPassTargetSummaryCard(
                            media = media,
                            config = config,
                            onTargetMbChange = viewModel::updateTargetMb,
                            onManualDurChange = viewModel::updateManualDuration
                        )
                    }
                    item {
                        CodecAndBitDepthSection(
                            media = media,
                            capabilities = capabilities,
                            selectedCodec = config.codec,
                            selectedBitDepth = config.bitDepth,
                            showCrfBadge = false,
                            onSelectCodec = viewModel::updateCodec,
                            onSelectBitDepth = viewModel::updateBitDepth
                        )
                    }
                    item {
                        val targetMb = when (config.mode) {
                            ProcessingMode.DISCORD_20MB -> 20.0
                            ProcessingMode.WHATSAPP_100MB -> 100.0
                            ProcessingMode.GMAIL_25MB -> 25.0
                            else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
                        }
                        val dur = media.durationSec ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput)
                        val defAb = FfmpegMath.resolveDefaultAudioBitrate(
                            baseDefaultKbps = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb),
                            sourceKbps = media.audioBitrateKbps,
                            isLosslessSource = media.isLosslessAudio
                        )
                        val abps = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
                        val baseVkbps = if (dur != null && dur > 0.0) {
                            FfmpegMath.calculateTwoPassVideoBitrateKbps(targetMb, dur, abps)
                        } else null
                        val adaptiveVkbps = if (dur != null && dur > 0.0) {
                            FfmpegMath.calculateAdaptiveTwoPassVideoBitrateKbps(
                                targetMb = targetMb,
                                durationSec = dur,
                                audioBitrateKbps = abps,
                                codec = config.codec,
                                width = media.width,
                                height = media.height,
                                resolution = config.resolution,
                                fpsOption = config.fps,
                                sourceFps = media.fps
                            ).first
                        } else null
                        val shouldRecommend = baseVkbps != null && dur != null &&
                            FfmpegMath.shouldRecommend30Fps720p(baseVkbps, config.codec, dur)

                        FpsAndResolutionSection(
                            media = media,
                            selectedFps = config.fps,
                            selectedResolution = config.resolution,
                            onSelectFps = viewModel::updateFps,
                            onSelectResolution = viewModel::updateResolution,
                            recommend30Fps720p = shouldRecommend,
                            calculatedVideoBitrateKbps = adaptiveVkbps
                        )
                    }
                    if (media.hasAudio) {
                        val targetMb = when (config.mode) {
                            ProcessingMode.DISCORD_20MB -> 20.0
                            ProcessingMode.WHATSAPP_100MB -> 100.0
                            ProcessingMode.GMAIL_25MB -> 25.0
                            else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
                        }
                        item {
                            AudioBitrateSection(
                                bitrateInput = config.audioBitrateInput,
                                defaultKbps = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb),
                                onBitrateChange = viewModel::updateAudioBitrate,
                                sourceAudioBitrateKbps = media.audioBitrateKbps,
                                sourceAudioCodec = media.audioCodec,
                                isLosslessSource = media.isLosslessAudio
                            )
                        }
                    }
                    item {
                        PresetSection(
                            selectedPreset = config.preset,
                            recommendedPreset = PresetOption.MEDIUM,
                            isTargetSizeMode = true,
                            onSelectPreset = viewModel::updatePreset
                        )
                    }
                }

                ProcessingMode.TRIM_VIDEO -> {
                    item {
                        TrimVideoPreviewCard(
                            media = media,
                            config = config,
                            onPlayVideoClick = onPlaySourceMedia,
                            scrubState = trimScrubState,
                            isActiveStage = isActiveStage
                        )
                    }
                    item {
                        TrimTimeAndKeyframeSection(
                            media = media,
                            config = config,
                            viewModel = viewModel,
                            scrubState = trimScrubState
                        )
                    }
                    item {
                        TrimSubModeSelector(
                            cutStrategy = config.trimCutStrategy,
                            selected = config.trimSubMode,
                            onSelect = viewModel::updateTrimSubMode
                        )
                    }
                    val isTrimCrf = config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY ||
                        config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC
                    if (isTrimCrf) {
                        item {
                            CodecAndBitDepthSection(
                                media = media,
                                capabilities = capabilities,
                                selectedCodec = config.codec,
                                selectedBitDepth = config.bitDepth,
                                showCrfBadge = true,
                                onSelectCodec = viewModel::updateCodec,
                                onSelectBitDepth = viewModel::updateBitDepth
                            )
                        }
                        item {
                            FpsAndResolutionSection(
                                media = media,
                                selectedFps = config.fps,
                                selectedResolution = config.resolution,
                                onSelectFps = viewModel::updateFps,
                                onSelectResolution = viewModel::updateResolution
                            )
                        }
                    }
                    if (media.hasAudio && (config.trimSubMode == TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC ||
                            config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC)
                    ) {
                        item {
                            AudioBitrateSection(
                                bitrateInput = config.audioBitrateInput,
                                defaultKbps = 192,
                                onBitrateChange = viewModel::updateAudioBitrate,
                                sourceAudioBitrateKbps = media.audioBitrateKbps,
                                sourceAudioCodec = media.audioCodec,
                                isLosslessSource = media.isLosslessAudio
                            )
                        }
                    }
                    if (isTrimCrf) {
                        item {
                            PresetSection(
                                selectedPreset = config.preset,
                                recommendedPreset = PresetOption.FAST,
                                isTargetSizeMode = false,
                                onSelectPreset = viewModel::updatePreset
                            )
                        }
                    }
                }

                ProcessingMode.REMUX_FORMAT -> {
                    item {
                        ConfigSectionCard(
                            title = strings.remuxCardTitle,
                            subtitle = strings.remuxCardSub
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = extColors.lossless,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = strings.remuxCardHighlight,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = extColors.lossless
                                )
                            }
                        }
                    }
                }

                ProcessingMode.AUDIO_ONLY_AAC -> {
                    item {
                        ConfigSectionCard(
                            title = strings.audioOnlyCardTitle,
                            subtitle = strings.audioOnlyCardSub(media.audioCodec?.uppercase() ?: "?")
                        ) {
                            Text(
                                text = strings.audioOnlyCardBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        AudioBitrateSection(
                            bitrateInput = config.audioBitrateInput,
                            defaultKbps = 256,
                            onBitrateChange = viewModel::updateAudioBitrate,
                            sourceAudioBitrateKbps = media.audioBitrateKbps,
                            sourceAudioCodec = media.audioCodec,
                            isLosslessSource = media.isLosslessAudio
                        )
                    }
                }

                ProcessingMode.VIDEO_ONLY_CRF -> {
                    item {
                        CodecAndBitDepthSection(
                            media = media,
                            capabilities = capabilities,
                            selectedCodec = config.codec,
                            selectedBitDepth = config.bitDepth,
                            showCrfBadge = true,
                            onSelectCodec = viewModel::updateCodec,
                            onSelectBitDepth = viewModel::updateBitDepth
                        )
                    }
                    item {
                        ConfigSectionCard(
                            title = strings.videoOnlyAudioTitle,
                            subtitle = if (media.hasAudio) {
                                strings.videoOnlyAudioCopySub(media.audioCodec?.uppercase() ?: "?")
                            } else {
                                strings.videoOnlyNoAudioSub
                            }
                        ) {
                            Text(
                                text = strings.videoOnlyCrfBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        PresetSection(
                            selectedPreset = config.preset,
                            recommendedPreset = PresetOption.FAST,
                            isTargetSizeMode = false,
                            onSelectPreset = viewModel::updatePreset
                        )
                    }
                }

                ProcessingMode.VIDEO_AND_AUDIO_CRF -> {
                    item {
                        CodecAndBitDepthSection(
                            media = media,
                            capabilities = capabilities,
                            selectedCodec = config.codec,
                            selectedBitDepth = config.bitDepth,
                            showCrfBadge = true,
                            onSelectCodec = viewModel::updateCodec,
                            onSelectBitDepth = viewModel::updateBitDepth
                        )
                    }
                    if (media.hasAudio) {
                        item {
                            AudioBitrateSection(
                                bitrateInput = config.audioBitrateInput,
                                defaultKbps = 256,
                                onBitrateChange = viewModel::updateAudioBitrate,
                                sourceAudioBitrateKbps = media.audioBitrateKbps,
                                sourceAudioCodec = media.audioCodec,
                                isLosslessSource = media.isLosslessAudio
                            )
                        }
                    }
                    item {
                        PresetSection(
                            selectedPreset = config.preset,
                            recommendedPreset = PresetOption.FAST,
                            isTargetSizeMode = false,
                            onSelectPreset = viewModel::updatePreset
                        )
                    }
                }

                ProcessingMode.EXTRACT_AUDIO -> {
                    item {
                        AudioExtractSection(
                            media = media,
                            selectedFormat = config.audioExtractFormat,
                            onSelectFormat = viewModel::updateAudioExtractFormat
                        )
                    }
                    val needsBitrate = (config.audioExtractFormat == AudioExtractFormat.AAC &&
                        !media.audioCodec.equals("aac", ignoreCase = true)) ||
                        (config.audioExtractFormat == AudioExtractFormat.MP3 &&
                            !media.audioCodec.equals("mp3", ignoreCase = true))
                    if (needsBitrate) {
                        item {
                            AudioBitrateSection(
                                bitrateInput = config.audioBitrateInput,
                                defaultKbps = 256,
                                onBitrateChange = viewModel::updateAudioBitrate,
                                title = strings.audioBitrateExtractTitle(config.audioExtractFormat.localizedLabel(strings)),
                                maxKbps = if (config.audioExtractFormat == AudioExtractFormat.MP3) 320 else 768,
                                sourceAudioBitrateKbps = media.audioBitrateKbps,
                                sourceAudioCodec = media.audioCodec,
                                isLosslessSource = media.isLosslessAudio
                            )
                        }
                    }
                }

                ProcessingMode.CUSTOM_COMMAND -> {
                    item {
                        CustomCommandSection(
                            media = media,
                            commandInput = config.customCommandInput,
                            previewPlan = customCommandPreviewPlan,
                            onCommandChange = viewModel::updateCustomCommand
                        )
                    }
                }
            }

            item {
                OutputAndCommandPreviewCard(
                    outputFileName = outputFileName,
                    commandPreview = commandPreview
                )
            }
        }
    }
}

@Composable
private fun TwoPassTargetSummaryCard(
    media: MediaInfo,
    config: ModeConfigState,
    onTargetMbChange: (String) -> Unit,
    onManualDurChange: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val targetMb = when (config.mode) {
        ProcessingMode.DISCORD_20MB -> 20.0
        ProcessingMode.WHATSAPP_100MB -> 100.0
        ProcessingMode.GMAIL_25MB -> 25.0
        else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
    }
    val dur = media.durationSec ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput)
    val defAb = FfmpegMath.resolveDefaultAudioBitrate(
        baseDefaultKbps = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb),
        sourceKbps = media.audioBitrateKbps,
        isLosslessSource = media.isLosslessAudio
    )
    val reqAbps = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
    val adaptivePlan = if (dur != null && dur > 0.0) {
        FfmpegMath.calculateAdaptiveTwoPassPlan(
            targetMb = targetMb,
            durationSec = dur,
            audioBitrateKbps = reqAbps,
            codec = config.codec,
            width = media.width,
            height = media.height,
            resolution = config.resolution,
            fpsOption = config.fps,
            sourceFps = media.fps
        )
    } else null
    val vkbps = adaptivePlan?.videoBitrateKbps
    val abps = adaptivePlan?.audioBitrateKbps ?: reqAbps

    ConfigSectionCard(
        title = strings.twoPassTitle,
        subtitle = strings.twoPassSub
    ) {
        if (config.mode == ProcessingMode.CUSTOM_SIZE) {
            OutlinedTextField(
                value = config.targetMbInput,
                onValueChange = onTargetMbChange,
                label = { Text(strings.targetMbInputLabel) },
                suffix = { Text("MB", style = MonoLabelStyle) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_mb_input")
            )
            Spacer(Modifier.height(10.dp))
        }

        if (media.durationSec == null) {
            OutlinedTextField(
                value = config.manualDurationInput,
                onValueChange = onManualDurChange,
                label = { Text(strings.manualDurationLabel) },
                suffix = { Text(strings.secondsUnitShort, style = MonoLabelStyle) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_duration_input")
            )
            Spacer(Modifier.height(10.dp))
        }

        if (vkbps != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        text = strings.twoPassSummaryLine(
                            FfmpegMath.formatMbTag(targetMb),
                            config.codec.tag,
                            config.bitDepth.tag,
                            vkbps,
                            abps,
                            config.preset.value
                        ),
                        style = MonoLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (FfmpegMath.isBitrateWarningLow(vkbps)) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = strings.lowBitrateWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioExtractSection(
    media: MediaInfo,
    selectedFormat: AudioExtractFormat,
    onSelectFormat: (AudioExtractFormat) -> Unit
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val origExt = FfmpegMath.resolveOriginalAudioExtension(media.audioCodec)
    val aacLossless = media.audioCodec.equals("aac", ignoreCase = true)
    val mp3Lossless = media.audioCodec.equals("mp3", ignoreCase = true)

    ConfigSectionCard(
        title = strings.audioExtractTitle,
        subtitle = strings.audioExtractSub
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val items = listOf(
                Triple(
                    AudioExtractFormat.ORIGINAL,
                    strings.audioExtractOriginalOption(media.audioCodec?.uppercase() ?: "?", origExt),
                    true
                ),
                Triple(AudioExtractFormat.AAC, "AAC (.m4a)", aacLossless),
                Triple(AudioExtractFormat.MP3, "MP3 (.mp3)", mp3Lossless)
            )
            items.forEach { (fmt, label, isLossless) ->
                val isSelected = fmt == selectedFormat
                val statusColor = if (isLossless) extColors.lossless else MaterialTheme.colorScheme.secondary
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onSelectFormat(fmt) }
                        .testTag("audio_extract_${fmt.name}"),
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = if (isLossless) strings.badgeLossless else strings.badgeLossy,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomCommandSection(
    media: MediaInfo,
    commandInput: String,
    previewPlan: CustomCommandPlan?,
    onCommandChange: (String) -> Unit
) {
    val context = LocalContext.current
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val defaultOut = strings.customCmdDefaultOutput
    val twoPassTargetKbps = remember(media.durationSec) {
        FfmpegMath.calculateTwoPassVideoBitrateKbps(
            targetMb = 20.0,
            durationSec = media.durationSec ?: 60.0,
            audioBitrateKbps = if (media.hasAudio) 128 else 0
        )
    }

    ConfigSectionCard(
        title = strings.customCmdTitle,
        subtitle = strings.customCmdSub
    ) {
        // AI-ready reassurance tip banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp)
                )
                Text(
                    text = strings.customCmdAiTip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // One-tap Paste from Clipboard + Clear action bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipText = cm?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                    if (!clipText.isNullOrBlank()) {
                        onCommandChange(clipText)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("custom_cmd_paste_button"),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(strings.customCmdPasteButton)
            }

            if (commandInput.isNotBlank()) {
                OutlinedButton(
                    onClick = { onCommandChange("") },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("custom_cmd_clear_button"),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = strings.customCmdClearButton,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(strings.customCmdClearButton)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = commandInput,
            onValueChange = onCommandChange,
            label = { Text(strings.customCmdInputLabel) },
            placeholder = {
                Text(
                    text = strings.customCmdPlaceholder,
                    style = MonoCodeStyle
                )
            },
            textStyle = MonoCodeStyle,
            minLines = 5,
            maxLines = 12,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_command_input")
        )

        // Live smart-detection & auto-adaptation badges
        if (commandInput.isNotBlank() && previewPlan != null) {
            Spacer(Modifier.height(10.dp))
            if (previewPlan.steps.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = strings.customCmdNoValidCommandWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                val adapt = previewPlan.adaptations
                val statusBadges = buildList {
                    if (adapt.autoBoundInputsCount > 0) add(strings.customCmdBadgeInputAutoBound)
                    if (adapt.autoConfiguredTwoPassLogs) add(strings.customCmdBadgeTwoPassReady)
                    if (adapt.strippedMarkdownOrProse) add(strings.customCmdBadgeMarkdownCleaned)
                    if (adapt.mappedWindowsNull) add(strings.customCmdBadgeWindowsNullFixed)
                    if (adapt.autoAddedOutputFileName != null) add(strings.customCmdBadgeOutputAutoAdded)
                    if (adapt.fixedSyntaxOrTyposCount > 0) add(strings.customCmdBadgeSyntaxFixed)
                    if (adapt.adaptedDesktopCodecCount > 0) add(strings.customCmdBadgeAndroidCodecAdapted)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = extColors.losslessContainer,
                        border = BorderStroke(1.dp, extColors.lossless.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = extColors.lossless,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = strings.customCmdStepsDetected(previewPlan.steps.size),
                                style = MonoLabelStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = extColors.onLosslessContainer
                            )
                        }
                    }

                    statusBadges.forEach { badgeText ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, extColors.subtleBorder)
                        ) {
                            Text(
                                text = badgeText,
                                style = MonoLabelStyle.copy(fontSize = 10.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = strings.customCmdShortcutsLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val twoPassSample = buildString {
                append("ffmpeg -y -i input.mp4 -c:v libx264 -b:v ${twoPassTargetKbps}k -pass 1 -an -f null /dev/null && \\\n")
                append("ffmpeg -y -i input.mp4 -c:v libx264 -b:v ${twoPassTargetKbps}k -pass 2 ")
                if (media.hasAudio) append("-c:a aac -b:a 128k ") else append("-an ")
                append("-movflags +faststart $defaultOut")
            }
            val gifSample = "ffmpeg -y -i input.mp4 -vf \"fps=15,scale=480:-1:flags=lanczos,palettegen\" palette.png\n" +
                "ffmpeg -y -i input.mp4 -i palette.png -filter_complex \"fps=15,scale=480:-1:flags=lanczos[x];[x][1:v]paletteuse\" animasyon.gif"
            val remuxSample = "ffmpeg -y -i input.mp4 -c copy -movflags +faststart $defaultOut"
            val muteSample = "ffmpeg -y -i input.mp4 -c:v copy -an -movflags +faststart sessiz_$defaultOut"

            listOf(
                strings.customCmdQuickRemuxChip to remuxSample,
                strings.customCmdQuickTwoPassChip to twoPassSample,
                strings.customCmdQuickGifChip to gifSample,
                strings.customCmdQuickMuteChip to muteSample
            ).forEach { (chipLabel, template) ->
                AssistChip(
                    onClick = { onCommandChange(template) },
                    label = { Text(chipLabel, style = MonoLabelStyle) }
                )
            }
        }
    }
}
