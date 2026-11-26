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
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
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
import com.example.ui.theme.PlayfairDisplayFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily

private fun modeBrandDrawableRes(mode: ProcessingMode): Int? = when (mode) {
    ProcessingMode.DISCORD_20MB -> R.drawable.ic_brand_discord
    ProcessingMode.WHATSAPP_100MB -> R.drawable.ic_brand_whatsapp
    ProcessingMode.GMAIL_25MB -> R.drawable.ic_brand_gmail
    else -> null
}

private fun modeIcon(mode: ProcessingMode): ImageVector = when (mode) {
    ProcessingMode.DISCORD_20MB,
    ProcessingMode.WHATSAPP_100MB,
    ProcessingMode.GMAIL_25MB,
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
private fun ModeGlyphIcon(
    mode: ProcessingMode,
    contentDescription: String,
    tint: Color,
    size: Dp
) {
    val brandRes = modeBrandDrawableRes(mode)
    if (brandRes != null) {
        Icon(
            painter = painterResource(id = brandRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size)
        )
    } else {
        Icon(
            imageVector = modeIcon(mode),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
fun ModeCategoryHeader(
    category: ModeCategory,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val warmLightBrown = MaterialTheme.colorScheme.secondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(19.dp)
                .clip(RoundedCornerShape(50))
                .background(warmLightBrown.copy(alpha = 0.85f))
        )
        Text(
            text = category.localizedTitle(strings),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = PlayfairDisplayFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Italic,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.25.sp
            ),
            color = warmLightBrown
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = warmLightBrown.copy(alpha = 0.22f)
        )
    }
}

/**
 * Group 1: First 3 presets (Discord, WhatsApp, Gmail) as side-by-side vertical cards,
 * with a wide full-width horizontal card for Özel Boyut / Custom Size right underneath.
 */
@Composable
fun TargetSizeModeRow(
    modes: List<ProcessingMode>,
    mediaInfo: MediaInfo?,
    onSelectMode: (ProcessingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val presetModes = modes.filter { it != ProcessingMode.CUSTOM_SIZE }
    val customSizeMode = modes.firstOrNull { it == ProcessingMode.CUSTOM_SIZE }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (presetModes.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetModes.forEach { mode ->
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
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                ModeGlyphIcon(
                                    mode = mode,
                                    contentDescription = mode.localizedTitle(strings),
                                    tint = iconColor,
                                    size = 19.dp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
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

        if (customSizeMode != null) {
            val available = mediaInfo != null &&
                (!customSizeMode.requiresAudio || mediaInfo.hasAudio) &&
                (!customSizeMode.requiresVideo || mediaInfo.hasVideo)
            val iconColor = MaterialTheme.colorScheme.primary

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (available) 1f else 0.84f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onSelectMode(customSizeMode) }
                    .testTag("mode_card_${customSizeMode.number}"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LocalExtendedColors.current.subtleBorder),
                tonalElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    // Dead-center icon aligned vertically with the middle preset card above
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = modeIcon(customSizeMode),
                            contentDescription = customSizeMode.localizedTitle(strings),
                            tint = iconColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left wing: Title gracefully right-aligned toward the centered icon
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 25.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = customSizeMode.localizedTitle(strings),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.1).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End
                            )
                        }

                        // Right wing: Symmetrical MB pill beside center icon + right-edge chevron
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 25.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                            ) {
                                Text(
                                    text = customSizeMode.localizedShortSubtitle(strings),
                                    style = MonoLabelStyle.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
    }
}

/**
 * Group 2: Prominent full-width feature card for Videoyu Kes.
 * Icon left edge (`11.dp`) and right badge edge (`31.dp`) align pixel-for-pixel with [OtherModesList].
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
                .heightIn(min = 78.dp)
                .padding(horizontal = 11.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = modeIcon(mode),
                    contentDescription = mode.localizedTitle(strings),
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = mode.localizedTitle(strings),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 16.5.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mode.localizedSubtitle(strings),
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 15.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            QualityStatusBadge(
                label = if (mediaInfo != null && !mediaInfo.hasVideo) strings.noVideoBadge else mode.localizedQualityTag(strings),
                tone = if (mediaInfo != null && !mediaInfo.hasVideo) QualityBadgeTone.LOSSY else mode.qualityTone,
                prominent = true
            )
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
    modifier: Modifier = Modifier,
    prominent: Boolean = false
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
        shape = RoundedCornerShape(if (prominent) 8.dp else 7.dp),
        color = bgColor,
        border = BorderStroke(if (prominent) 0.9.dp else 0.8.dp, borderColor)
    ) {
        Text(
            text = label,
            style = MonoLabelStyle.copy(
                fontSize = if (prominent) 11.5.sp else 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = if (prominent) 0.25.sp else 0.2.sp
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = if (prominent) 10.dp else 7.dp,
                vertical = if (prominent) 5.dp else 3.dp
            )
        )
    }
}
