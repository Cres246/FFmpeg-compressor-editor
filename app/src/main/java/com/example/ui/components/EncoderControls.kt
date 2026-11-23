package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.BitDepthOption
import com.example.domain.EncoderCapabilities
import com.example.domain.FfmpegMath
import com.example.domain.FpsOption
import com.example.domain.LocalAppStrings
import com.example.domain.MediaInfo
import com.example.domain.PresetOption
import com.example.domain.ResolutionOption
import com.example.domain.VideoCodecOption
import com.example.domain.localizedLabel
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoLabelStyle

@Composable
fun ConfigSectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val extColors = LocalExtendedColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extColors.subtleBorder),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodecAndBitDepthSection(
    media: MediaInfo,
    capabilities: EncoderCapabilities,
    selectedCodec: VideoCodecOption,
    selectedBitDepth: BitDepthOption,
    showCrfBadge: Boolean,
    onSelectCodec: (VideoCodecOption) -> Unit,
    onSelectBitDepth: (BitDepthOption) -> Unit
) {
    val strings = LocalAppStrings.current
    val supports10Bit = when (selectedCodec) {
        VideoCodecOption.H264 -> capabilities.x264Supports10Bit
        VideoCodecOption.H265 -> capabilities.x265Supports10Bit
    }
    val inputHighBit = media.bitDepthIn == "10bit" || media.bitDepthIn == "12bit"

    ConfigSectionCard(
        title = strings.codecSectionTitle,
        subtitle = strings.codecSectionSub(media.videoCodec?.uppercase() ?: "?", media.bitDepthIn)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VideoCodecOption.entries.forEach { codec ->
                val isSelected = codec == selectedCodec
                val detail = if (showCrfBadge) "CRF ${codec.crf}" else codec.encoder
                SelectableOptionTile(
                    title = codec.label,
                    subtitle = detail,
                    selected = isSelected,
                    onClick = { onSelectCodec(codec) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("codec_${codec.tag}")
                )
            }
        }

        if (supports10Bit) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = strings.colorBitDepthLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val label8 = when {
                    selectedCodec == VideoCodecOption.H264 -> strings.bit8Compatible
                    !inputHighBit -> strings.bit8Default
                    else -> "8-bit"
                }
                val label10 = when {
                    selectedCodec == VideoCodecOption.H264 -> strings.bit10VlcOnly
                    inputHighBit -> strings.bit10Preserve
                    else -> "10-bit"
                }
                SelectableOptionTile(
                    title = label8,
                    subtitle = "yuv420p",
                    selected = selectedBitDepth == BitDepthOption.BIT_8,
                    onClick = { onSelectBitDepth(BitDepthOption.BIT_8) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bit_depth_8")
                )
                SelectableOptionTile(
                    title = label10,
                    subtitle = "yuv420p10le",
                    selected = selectedBitDepth == BitDepthOption.BIT_10,
                    onClick = { onSelectBitDepth(BitDepthOption.BIT_10) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bit_depth_10")
                )
            }

            if (selectedCodec == VideoCodecOption.H264 && selectedBitDepth == BitDepthOption.BIT_10) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = strings.h264TenBitWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FpsAndResolutionSection(
    media: MediaInfo,
    selectedFps: FpsOption,
    selectedResolution: ResolutionOption,
    onSelectFps: (FpsOption) -> Unit,
    onSelectResolution: (ResolutionOption) -> Unit
) {
    val strings = LocalAppStrings.current
    val computedScale = FfmpegMath.buildScaleFilter(
        width = media.width,
        height = media.height,
        resolution = selectedResolution,
        needsEvenScale = media.needsEvenScale
    )

    ConfigSectionCard(
        title = strings.fpsResSectionTitle,
        subtitle = strings.fpsResSectionSub
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FpsOption.entries.forEach { fps ->
                SelectableOptionTile(
                    title = fps.localizedLabel(strings),
                    subtitle = if (fps == FpsOption.FPS_30) "-r 30" else strings.fpsKeepSub,
                    selected = fps == selectedFps,
                    onClick = { onSelectFps(fps) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("fps_${fps.name}")
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = strings.targetResolutionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ResolutionOption.entries.forEach { res ->
                FilterChip(
                    selected = res == selectedResolution,
                    onClick = { onSelectResolution(res) },
                    label = { Text(res.localizedLabel(strings), style = MonoLabelStyle) },
                    modifier = Modifier.testTag("res_${res.name}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        if (computedScale != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${strings.activeFilterPrefix} -vf $computedScale",
                style = MonoLabelStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetSection(
    selectedPreset: PresetOption,
    recommendedPreset: PresetOption,
    onSelectPreset: (PresetOption) -> Unit
) {
    val strings = LocalAppStrings.current
    ConfigSectionCard(
        title = strings.presetSectionTitle,
        subtitle = strings.presetSectionSub(recommendedPreset.value)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PresetOption.entries.forEach { preset ->
                val isRec = preset == recommendedPreset
                FilterChip(
                    selected = preset == selectedPreset,
                    onClick = { onSelectPreset(preset) },
                    label = {
                        Text(
                            text = if (isRec) "${preset.label} ★" else preset.label,
                            style = MonoLabelStyle
                        )
                    },
                    modifier = Modifier.testTag("preset_${preset.value}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioBitrateSection(
    bitrateInput: String,
    defaultKbps: Int,
    onBitrateChange: (String) -> Unit,
    title: String? = null
) {
    val strings = LocalAppStrings.current
    val resolvedTitle = title ?: strings.audioBitrateTitle
    val quickValues = listOf("64", "128", "192", "256", "320", "384")
    val normalized = FfmpegMath.normalizeAudioBitrate(bitrateInput, defaultKbps)

    ConfigSectionCard(
        title = resolvedTitle,
        subtitle = strings.audioBitrateSub(normalized)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            quickValues.forEach { preset ->
                val selected = bitrateInput.trim().replace("k", "", ignoreCase = true) == preset
                FilterChip(
                    selected = selected,
                    onClick = { onBitrateChange(preset) },
                    label = {
                        Text(
                            text = if (preset == defaultKbps.toString()) "${preset}k ★" else "${preset}k",
                            style = MonoLabelStyle
                        )
                    },
                    modifier = Modifier.testTag("audio_kbps_$preset"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = bitrateInput,
            onValueChange = onBitrateChange,
            label = { Text(strings.customBitrateLabel) },
            suffix = { Text("k", style = MonoLabelStyle) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("audio_bitrate_input")
        )
    }
}

@Composable
fun SelectableOptionTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else LocalExtendedColors.current.subtleBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MonoLabelStyle,
                color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun OutputAndCommandPreviewCard(
    outputFileName: String,
    commandPreview: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, extColors.subtleBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = strings.outputFileNameHeader,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = outputFileName,
                style = MonoCodeStyle.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (commandPreview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.ffmpegCommandPreview,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) strings.actionHide else strings.actionShow,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, extColors.subtleBorder)
                    ) {
                        Text(
                            text = commandPreview,
                            style = MonoCodeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}
