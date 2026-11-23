package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AudioExtractFormat
import com.example.domain.EncoderCapabilities
import com.example.domain.FfmpegMath
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.PresetOption
import com.example.domain.ProcessingMode
import com.example.domain.TrimSubMode
import com.example.ui.MainViewModel
import com.example.ui.components.AudioBitrateSection
import com.example.ui.components.CodecAndBitDepthSection
import com.example.ui.components.ConfigSectionCard
import com.example.ui.components.FpsAndResolutionSection
import com.example.ui.components.OutputAndCommandPreviewCard
import com.example.ui.components.PresetSection
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
    isActiveStage: Boolean = true,
    onPlaySourceMedia: () -> Unit = {},
    onStartClick: () -> Unit
) {
    BackHandler(enabled = isActiveStage) { viewModel.closeConfig() }

    val extColors = LocalExtendedColors.current
    val outputFileName = remember(media, config) {
        FfmpegMath.computeOutputFileName(media, config)
    }
    val commandPreview = remember(media, config) {
        if (config.mode == ProcessingMode.CUSTOM_COMMAND) {
            config.customCommandInput.trim()
        } else {
            val steps = FfmpegMath.buildFfmpegSteps(
                media = media,
                config = config,
                inputTokenProvider = { "\$IN" },
                outputPath = outputFileName,
                passLogPrefix = "\$PASSDIR/ff2pass"
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
                            text = "${config.mode.formattedNumber} · ${config.mode.title}",
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
                            contentDescription = stringResource(R.string.action_back)
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
                    if (!validationError.isNullOrBlank()) {
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
                                text = validationError,
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
                            text = stringResource(R.string.action_start),
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
            when (config.mode) {
                ProcessingMode.DISCORD_20MB,
                ProcessingMode.WHATSAPP_100MB,
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
                        FpsAndResolutionSection(
                            media = media,
                            selectedFps = config.fps,
                            selectedResolution = config.resolution,
                            onSelectFps = viewModel::updateFps,
                            onSelectResolution = viewModel::updateResolution
                        )
                    }
                    item {
                        PresetSection(
                            selectedPreset = config.preset,
                            recommendedPreset = PresetOption.MEDIUM,
                            onSelectPreset = viewModel::updatePreset
                        )
                    }
                    if (media.hasAudio) {
                        val targetMb = when (config.mode) {
                            ProcessingMode.DISCORD_20MB -> 20.0
                            ProcessingMode.WHATSAPP_100MB -> 100.0
                            else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
                        }
                        item {
                            AudioBitrateSection(
                                bitrateInput = config.audioBitrateInput,
                                defaultKbps = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb),
                                onBitrateChange = viewModel::updateAudioBitrate
                            )
                        }
                    }
                }

                ProcessingMode.TRIM_VIDEO -> {
                    item {
                        TrimVideoPreviewCard(
                            media = media,
                            config = config,
                            onPlayVideoClick = onPlaySourceMedia
                        )
                    }
                    item {
                        TrimTimeAndKeyframeSection(
                            media = media,
                            config = config,
                            viewModel = viewModel
                        )
                    }
                    item {
                        TrimSubModeSelector(
                            cutStrategy = config.trimCutStrategy,
                            selected = config.trimSubMode,
                            onSelect = viewModel::updateTrimSubMode
                        )
                    }
                    if (config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY ||
                        config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC
                    ) {
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
                        item {
                            PresetSection(
                                selectedPreset = config.preset,
                                recommendedPreset = PresetOption.FAST,
                                onSelectPreset = viewModel::updatePreset
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
                                onBitrateChange = viewModel::updateAudioBitrate
                            )
                        }
                    }
                }

                ProcessingMode.REMUX_FORMAT -> {
                    item {
                        ConfigSectionCard(
                            title = "%100 Kayıpsız MP4 Paketleme",
                            subtitle = "Görüntü ve sese dokunmadan (-c copy) MP4 kabı yenilenir, moov başlığı dosya başına alınır (+faststart) ve uyumsuz altyazı/veri izleri elenir (-sn -dn)."
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
                                    text = "Saniyeler içinde tamamlanır · Sıfır kalite kaybı",
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
                            title = "Görüntü %100 Kayıpsız Kopyalanır",
                            subtitle = "Yalnızca ses akışı (${media.audioCodec?.uppercase() ?: "?"}) seçilen bitrate'te AAC'ye çevrilir (-c:v copy)."
                        ) {
                            Text(
                                text = "Opus, DTS, AC3 veya EAC3 sesli videoları telefon, WhatsApp ve MP4 oynatıcılarla tam uyumlu yapmak için idealdir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        AudioBitrateSection(
                            bitrateInput = config.audioBitrateInput,
                            defaultKbps = 256,
                            onBitrateChange = viewModel::updateAudioBitrate
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
                        PresetSection(
                            selectedPreset = config.preset,
                            recommendedPreset = PresetOption.FAST,
                            onSelectPreset = viewModel::updatePreset
                        )
                    }
                    item {
                        ConfigSectionCard(
                            title = "Ses Akışı",
                            subtitle = if (media.hasAudio) {
                                "Kaynak ses (${media.audioCodec?.uppercase()}) yeniden kodlanmadan %100 kayıpsız kopyalanır (-c:a copy)."
                            } else {
                                "Videoda ses izi yok (-an)."
                            }
                        ) {
                            Text(
                                text = "H.264 için CRF 16, H.265 için buna denk arşiv kalitesi CRF 20 uygulanır.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    item {
                        PresetSection(
                            selectedPreset = config.preset,
                            recommendedPreset = PresetOption.FAST,
                            onSelectPreset = viewModel::updatePreset
                        )
                    }
                    if (media.hasAudio) {
                        item {
                            AudioBitrateSection(
                                bitrateInput = config.audioBitrateInput,
                                defaultKbps = 256,
                                onBitrateChange = viewModel::updateAudioBitrate
                            )
                        }
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
                                title = "Çıktı Ses Bitrate (${config.audioExtractFormat.label})"
                            )
                        }
                    }
                }

                ProcessingMode.CUSTOM_COMMAND -> {
                    item {
                        CustomCommandSection(
                            media = media,
                            commandInput = config.customCommandInput,
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
    val targetMb = when (config.mode) {
        ProcessingMode.DISCORD_20MB -> 20.0
        ProcessingMode.WHATSAPP_100MB -> 100.0
        else -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
    }
    val dur = media.durationSec ?: FfmpegMath.parsePositiveDouble(config.manualDurationInput)
    val defAb = FfmpegMath.defaultAudioBitrateForTwoPass(targetMb)
    val abps = if (!media.hasAudio) 0 else FfmpegMath.normalizeAudioBitrate(config.audioBitrateInput, defAb)
    val vkbps = if (dur != null && dur > 0.0) {
        FfmpegMath.calculateTwoPassVideoBitrateKbps(targetMb, dur, abps)
    } else null

    ConfigSectionCard(
        title = "Gerçek 2-Pass Hedef Boyut Hesabı",
        subtitle = "Tek seferlik %4 güvenlik payı (MB × 8000 × 0.96) ile kapsayıcı taşması önlenir"
    ) {
        if (config.mode == ProcessingMode.CUSTOM_SIZE) {
            OutlinedTextField(
                value = config.targetMbInput,
                onValueChange = onTargetMbChange,
                label = { Text("Hedef Boyut (MB) · Örn: 1, 8, 25") },
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
                label = { Text("Süre okunamadı · Saniye olarak girin") },
                suffix = { Text("sn", style = MonoLabelStyle) },
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
                        text = "Hedef: < ${FfmpegMath.formatMbTag(targetMb)} MB  |  ${config.codec.tag}${config.bitDepth.tag} ${vkbps}k  |  Ses: ${abps}k  |  ${config.preset.value}",
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
                                text = "Not: Hedef/ses oranına göre video bitrati çok düşük, boyut aşabilir.",
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
    val origExt = FfmpegMath.resolveOriginalAudioExtension(media.audioCodec)
    val aacLossless = media.audioCodec.equals("aac", ignoreCase = true)
    val mp3Lossless = media.audioCodec.equals("mp3", ignoreCase = true)

    ConfigSectionCard(
        title = "Ses Çıkarma Formatı",
        subtitle = "Kaynak ses zaten AAC veya MP3 ise yeniden kodlanmadan %100 kayıpsız kopyalanır"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val items = listOf(
                Triple(
                    AudioExtractFormat.ORIGINAL,
                    "Orijinal (${media.audioCodec?.uppercase() ?: "?"} → .$origExt)",
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
                                text = if (isLossless) "Kayıpsız" else "Kayıplı",
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
    onCommandChange: (String) -> Unit
) {
    val inputAlias = if (media.extension.isNotBlank()) "input.${media.extension}" else "input.mp4"

    ConfigSectionCard(
        title = "Özel FFmpeg Komutu (Tam Komut)",
        subtitle = "Çoklu && zincir komutları dahil · Oluşan çıktılar otomatik Movies/TermuxVideo klasörüne taşınır"
    ) {
        Text(
            text = "Girdi kısayolları (dokunarak ekle):",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "ffmpeg -y -i \"\$IN\" -c copy cikti.mp4" to "Hızlı Remux",
                "\"\$IN\"" to "\"\$IN\"",
                inputAlias to inputAlias,
                "cikti.mp4" to "cikti.mp4"
            ).forEach { (snippet, chipLabel) ->
                AssistChip(
                    onClick = {
                        val updated = if (commandInput.isBlank() || chipLabel == "Hızlı Remux") {
                            snippet
                        } else {
                            "$commandInput $snippet"
                        }
                        onCommandChange(updated)
                    },
                    label = { Text(chipLabel, style = MonoLabelStyle) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = commandInput,
            onValueChange = onCommandChange,
            label = { Text("FFmpeg Komutu") },
            placeholder = {
                Text(
                    "ffmpeg -y -i \"\$IN\" -c:v libx264 -crf 18 -c:a copy cikti.mp4",
                    style = MonoCodeStyle
                )
            },
            textStyle = MonoCodeStyle,
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_command_input")
        )
    }
}
