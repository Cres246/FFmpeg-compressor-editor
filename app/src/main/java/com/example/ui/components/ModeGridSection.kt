package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.LocalAppStrings
import com.example.domain.MediaInfo
import com.example.domain.ModeCategory
import com.example.domain.ProcessingMode
import com.example.domain.QualityBadgeTone
import com.example.domain.localizedQualityTag
import com.example.domain.localizedShortSubtitle
import com.example.domain.localizedShortTitle
import com.example.domain.localizedSubtitle
import com.example.domain.localizedTitle
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoLabelStyle
import java.util.Locale

private fun modeIcon(mode: ProcessingMode): ImageVector = when (mode) {
    ProcessingMode.DISCORD_20MB -> Icons.Rounded.Forum
    ProcessingMode.WHATSAPP_100MB -> Icons.Rounded.Chat
    ProcessingMode.CUSTOM_SIZE -> Icons.Rounded.Compress
    ProcessingMode.TRIM_VIDEO -> Icons.Rounded.ContentCut
    ProcessingMode.REMUX_FORMAT -> Icons.Rounded.SwapHoriz
    ProcessingMode.AUDIO_ONLY_AAC -> Icons.Rounded.GraphicEq
    ProcessingMode.VIDEO_ONLY_CRF -> Icons.Rounded.HighQuality
    ProcessingMode.VIDEO_AND_AUDIO_CRF -> Icons.Rounded.AutoFixHigh
    ProcessingMode.EXTRACT_AUDIO -> Icons.Rounded.MusicNote
    ProcessingMode.CUSTOM_COMMAND -> Icons.Rounded.Terminal
}

@Composable
fun ModeCategoryHeader(
    category: ModeCategory,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val upperLocale = if (strings.language.code == "tr") Locale("tr", "TR") else Locale.US
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = category.localizedTitle(strings).uppercase(upperLocale),
            style = MonoLabelStyle.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = LocalExtendedColors.current.subtleBorder
        )
    }
}

/**
 * Group 1: 3-column horizontal row for Hedef Boyut (Discord, WhatsApp, Özel Boyut).
 */
@Composable
fun TargetSizeModeRow(
    modes: List<ProcessingMode>,
    mediaInfo: MediaInfo?,
    onSelectMode: (ProcessingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.forEach { mode ->
            val available = mediaInfo != null &&
                (!mode.requiresAudio || mediaInfo.hasAudio) &&
                (!mode.requiresVideo || mediaInfo.hasVideo)
            val iconColor = MaterialTheme.colorScheme.primary

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (available) 1f else 0.84f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelectMode(mode) }
                    .testTag("mode_card_${mode.number}"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LocalExtendedColors.current.subtleBorder),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = modeIcon(mode),
                            contentDescription = mode.localizedTitle(strings),
                            tint = iconColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = mode.localizedShortTitle(strings),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = mode.localizedShortSubtitle(strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Group 2: Sleek full-width feature card for Videoyu Kes.
 */
@Composable
fun TrimFeatureCard(
    mode: ProcessingMode,
    mediaInfo: MediaInfo?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val available = mediaInfo != null && (!mode.requiresVideo || mediaInfo.hasVideo)
    val iconColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (available) 1f else 0.84f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("mode_card_${mode.number}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalExtendedColors.current.subtleBorder),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = modeIcon(mode),
                    contentDescription = mode.localizedTitle(strings),
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.localizedTitle(strings),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mode.localizedSubtitle(strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            QualityStatusBadge(
                label = if (mediaInfo != null && !mediaInfo.hasVideo) strings.noVideoBadge else mode.localizedQualityTag(strings),
                tone = if (mediaInfo != null && !mediaInfo.hasVideo) QualityBadgeTone.LOSSY else mode.qualityTone
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Group 3: Sleek vertical single-column list for Diğer İşlemler / More Tools.
 */
@Composable
fun OtherModesList(
    modes: List<ProcessingMode>,
    mediaInfo: MediaInfo?,
    onSelectMode: (ProcessingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        modes.forEach { mode ->
            val missingAudio = mediaInfo != null && mode.requiresAudio && !mediaInfo.hasAudio
            val missingVideo = mediaInfo != null && mode.requiresVideo && !mediaInfo.hasVideo
            val available = mediaInfo != null && !missingAudio && !missingVideo
            val iconColor = MaterialTheme.colorScheme.primary

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (available) 1f else 0.84f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onSelectMode(mode) }
                    .testTag("mode_card_${mode.number}"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LocalExtendedColors.current.subtleBorder),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = modeIcon(mode),
                            contentDescription = mode.localizedShortTitle(strings),
                            tint = iconColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = mode.localizedShortTitle(strings),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    val (badgeLabel, badgeTone) = when {
                        missingVideo -> strings.noVideoBadge to QualityBadgeTone.LOSSY
                        missingAudio -> strings.noAudioBadge to QualityBadgeTone.LOSSY
                        else -> mode.localizedQualityTag(strings) to mode.qualityTone
                    }
                    if (badgeLabel.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        QualityStatusBadge(
                            label = badgeLabel,
                            tone = badgeTone
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityStatusBadge(
    label: String,
    tone: QualityBadgeTone,
    modifier: Modifier = Modifier
) {
    val extColors = LocalExtendedColors.current
    val (bgColor, textColor, borderColor) = when (tone) {
        QualityBadgeTone.LOSSLESS -> Triple(
            extColors.losslessContainer,
            extColors.lossless,
            extColors.lossless.copy(alpha = 0.28f)
        )
        QualityBadgeTone.PARTIAL -> Triple(
            extColors.keyframeContainer,
            extColors.keyframe,
            extColors.keyframe.copy(alpha = 0.28f)
        )
        QualityBadgeTone.LOSSY -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
        )
        QualityBadgeTone.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            extColors.subtleBorder
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = bgColor,
        border = BorderStroke(0.8.dp, borderColor)
    ) {
        Text(
            text = label,
            style = MonoLabelStyle.copy(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
