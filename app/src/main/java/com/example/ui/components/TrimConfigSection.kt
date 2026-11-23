package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.FfmpegEngine
import com.example.domain.FfmpegMath
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.TrimCutStrategy
import com.example.domain.TrimSubMode
import com.example.ui.MainViewModel
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoLabelStyle
import kotlinx.coroutines.delay

/**
 * Top preview card on Trim screen: two side-by-side square frame thumbnails showing the exact
 * selected start & end frames, plus a compact play button below to open the video in any player.
 */
@Composable
fun TrimVideoPreviewCard(
    media: MediaInfo,
    config: ModeConfigState,
    onPlayVideoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extColors = LocalExtendedColors.current
    val (effStart, effEnd, _) = FfmpegMath.resolveTrimRange(config, media.durationSec)

    val parsedStartSec = effStart?.let { FfmpegMath.parseTimestampToSeconds(it) }
    val parsedEndSec = effEnd?.let { FfmpegMath.parseTimestampToSeconds(it) }

    var startSec by remember(media.uri) { mutableStateOf(0.0) }
    val nextStartSec = when {
        effStart == null -> 0.0
        parsedStartSec != null -> parsedStartSec
        else -> null
    }
    if (nextStartSec != null && nextStartSec != startSec) {
        startSec = nextStartSec
    }

    val defaultEndSec = (media.durationSec?.let { (it - 0.25).coerceAtLeast(0.0) } ?: 0.0)
    var endSec by remember(media.uri, media.durationSec) { mutableStateOf(defaultEndSec) }
    val nextEndSec = when {
        effEnd == null -> defaultEndSec
        parsedEndSec != null -> parsedEndSec
        else -> null
    }
    if (nextEndSec != null && nextEndSec != endSec) {
        endSec = nextEndSec
    }

    val startLabel = remember(effStart, startSec) {
        if (effStart == null) "00:00.0"
        else FfmpegMath.formatKeyframeTime(startSec)
    }
    val endLabel = remember(effEnd, endSec, media.durationSec) {
        when {
            effEnd != null -> FfmpegMath.formatKeyframeTime(endSec)
            media.durationSec != null -> FfmpegMath.formatKeyframeTime(media.durationSec)
            else -> "Son"
        }
    }

    var startBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    var startLoading by remember(media.uri) { mutableStateOf(true) }
    var endBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    var endLoading by remember(media.uri) { mutableStateOf(true) }

    LaunchedEffect(media.uri, startSec) {
        if (startBitmap != null) delay(280L)
        startLoading = true
        val extracted = FfmpegEngine.extractPreviewFrame(
            context = context,
            uri = media.uri,
            timeSec = startSec,
            durationSec = media.durationSec
        )
        if (extracted != null || startBitmap == null) {
            startBitmap = extracted
        }
        startLoading = false
    }

    LaunchedEffect(media.uri, endSec) {
        if (endBitmap != null) delay(280L)
        endLoading = true
        val extracted = FfmpegEngine.extractPreviewFrame(
            context = context,
            uri = media.uri,
            timeSec = endSec,
            durationSec = media.durationSec
        )
        if (extracted != null || endBitmap == null) {
            endBitmap = extracted
        }
        endLoading = false
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extColors.subtleBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SquareFramePreviewBox(
                    tag = "BAŞLANGIÇ",
                    timeText = startLabel,
                    bitmap = startBitmap,
                    isLoading = startLoading,
                    onClick = onPlayVideoClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_preview_start_frame")
                )
                SquareFramePreviewBox(
                    tag = "BİTİŞ",
                    timeText = endLabel,
                    bitmap = endBitmap,
                    isLoading = endLoading,
                    onClick = onPlayVideoClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_preview_end_frame")
                )
            }

            FilledTonalButton(
                onClick = onPlayVideoClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .testTag("trim_play_external_button"),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Videoyu Oynat",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SquareFramePreviewBox(
    tag: String,
    timeText: String,
    bitmap: Bitmap?,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalExtendedColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$tag karesi ($timeText)",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!isLoading) {
            Icon(
                imageVector = Icons.Rounded.Videocam,
                contentDescription = "$tag karesi",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp)
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (bitmap != null) 0.22f else 0.04f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = if (bitmap != null) Color.White else MaterialTheme.colorScheme.primary
                )
            }
        }

        // Bottom gradient scrim + crisp timestamp label
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tag,
                    style = MonoLabelStyle.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1
                )
                Text(
                    text = timeText,
                    style = MonoLabelStyle.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        // Subtle inner border
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, extColors.subtleBorder)
        ) {}
    }
}

/**
 * Clean trim time inputs + side-by-side Kayıpsız Kes / Hassas Kes selector + contextual
 * explanation + centered 4-column keyframe grid (no cluttered section title header).
 */
@Composable
fun TrimTimeAndKeyframeSection(
    media: MediaInfo,
    config: ModeConfigState,
    viewModel: MainViewModel
) {
    val extColors = LocalExtendedColors.current
    val (effStart, effEnd, clipDur) = FfmpegMath.resolveTrimRange(config, media.durationSec)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extColors.subtleBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Selected trim range summary bar at top
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        val startLabel = effStart?.let {
                            val parsed = FfmpegMath.parseTimestampToSeconds(it)
                            if (parsed != null && !it.contains(':')) FfmpegMath.formatKeyframeTime(parsed) else it
                        } ?: "00:00 (Baş)"
                        val endLabel = effEnd?.let {
                            val parsed = FfmpegMath.parseTimestampToSeconds(it)
                            if (parsed != null && !it.contains(':')) FfmpegMath.formatKeyframeTime(parsed) else it
                        } ?: "Son"
                        Text(
                            text = "$startLabel  →  $endLabel",
                            style = MonoLabelStyle.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (clipDur != null) {
                        Text(
                            text = FfmpegMath.formatDurationShort(clipDur),
                            style = MonoLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Start & End time inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = config.startTimeInput,
                    onValueChange = viewModel::updateStartTime,
                    label = { Text("Başlangıç") },
                    placeholder = { Text("00:00 (Baş)", style = MonoLabelStyle) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_start_input")
                )
                OutlinedTextField(
                    value = config.endTimeInput,
                    onValueChange = viewModel::updateEndTime,
                    label = { Text("Bitiş") },
                    placeholder = { Text("Son", style = MonoLabelStyle) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_end_input")
                )
            }

            Spacer(Modifier.height(12.dp))

            // Two side-by-side strategy cards: Kayıpsız Kes | Hassas Kes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrimStrategyBox(
                    strategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
                    selected = config.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME,
                    accentColor = extColors.lossless,
                    accentContainer = extColors.losslessContainer,
                    onClick = { viewModel.selectTrimCutStrategy(TrimCutStrategy.LOSSLESS_KEYFRAME) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_strategy_lossless")
                )
                TrimStrategyBox(
                    strategy = TrimCutStrategy.PRECISE_REENCODE,
                    selected = config.trimCutStrategy == TrimCutStrategy.PRECISE_REENCODE,
                    accentColor = extColors.keyframe,
                    accentContainer = extColors.keyframeContainer,
                    onClick = { viewModel.selectTrimCutStrategy(TrimCutStrategy.PRECISE_REENCODE) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_strategy_precise")
                )
            }

            Spacer(Modifier.height(10.dp))

            if (config.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME) {
                // Concise explanation for lossless keyframe cutting
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = extColors.losslessContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, extColors.lossless.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = extColors.lossless,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Keyframe noktalarından kayıpsız kesilir; görüntü yeniden kodlanmadığı için orijinal kalır ve dosya boyutu kesilen süre oranında aynı kalır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extColors.onLosslessContainer
                        )
                    }
                }

                val hasStartInput = config.startTimeInput.trim().isNotEmpty()
                val hasEndInput = config.endTimeInput.trim().isNotEmpty()
                val needsScan = (hasStartInput && config.trimScan.startScan == null) ||
                    (hasEndInput && config.trimScan.endScan == null)

                if (config.trimScan.isScanning || needsScan) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = { viewModel.scanTrimKeyframes() },
                        enabled = !config.trimScan.isScanning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("scan_keyframes_button"),
                        shape = MaterialTheme.shapes.small
                    ) {
                        if (config.trimScan.isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Keyframe noktaları taranıyor...")
                        } else {
                            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Keyframe Noktalarını Göster")
                        }
                    }
                }

                config.trimScan.error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val startScan = config.trimScan.startScan
                val endScan = config.trimScan.endScan
                if (startScan != null || endScan != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, extColors.subtleBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (startScan != null) {
                                val activeStartKf = config.selectedStartKfSec ?: startScan.prevKf
                                KeyframeGridBlock(
                                    label = "Başlangıç Keyframe'leri",
                                    keyframes = FfmpegMath.selectCenteredKeyframes(
                                        keyframes = startScan.nearbyKeyframes,
                                        targetSec = startScan.targetSec,
                                        maxCount = 12
                                    ),
                                    selectedKf = activeStartKf,
                                    onSelectKeyframe = viewModel::applyNearbyKeyframeToStart
                                )
                            }
                            if (endScan != null) {
                                val activeEndKf = config.selectedEndKfSec ?: endScan.nextKf
                                KeyframeGridBlock(
                                    label = "Bitiş Keyframe'leri",
                                    keyframes = FfmpegMath.selectCenteredKeyframes(
                                        keyframes = endScan.nearbyKeyframes,
                                        targetSec = endScan.targetSec,
                                        maxCount = 12
                                    ),
                                    selectedKf = activeEndKf,
                                    onSelectKeyframe = viewModel::applyNearbyKeyframeToEnd
                                )
                            }
                        }
                    }
                }
            } else {
                // Concise explanation for precise CRF re-encoding
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = extColors.keyframeContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, extColors.keyframe.copy(alpha = 0.28f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = extColors.keyframe,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Video tam girdiğiniz saniyeden yeniden kodlanacağı için dosya boyutu epey büyüyebilir. CRF 16 (HEVC için CRF 20) ile kodlandığından neredeyse gözle görülemeyecek seviyede görüntü kaybı olur.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extColors.onKeyframeContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrimStrategyBox(
    strategy: TrimCutStrategy,
    selected: Boolean,
    accentColor: Color,
    accentContainer: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalExtendedColors.current
    val icon = if (strategy == TrimCutStrategy.LOSSLESS_KEYFRAME) Icons.Rounded.Bolt else Icons.Rounded.Tune

    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) accentContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 1.6.dp else 1.dp,
            color = if (selected) accentColor else extColors.subtleBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = strategy.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = strategy.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KeyframeGridBlock(
    label: String,
    keyframes: List<Double>,
    selectedKf: Double?,
    onSelectKeyframe: (Double) -> Unit
) {
    val extColors = LocalExtendedColors.current
    val rows = keyframes.chunked(4)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (rows.isEmpty()) {
            Text(
                text = "Yakında keyframe bulunamadı",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowItems.forEach { kf ->
                        val isSelected = selectedKf != null && kotlin.math.abs(kf - selectedKf) < 0.02
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { onSelectKeyframe(kf) },
                            shape = RoundedCornerShape(9.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = "Seçili keyframe",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                }
                                Text(
                                    text = FfmpegMath.formatKeyframeTime(kf),
                                    style = MonoLabelStyle.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    repeat(4 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun TrimSubModeSelector(
    cutStrategy: TrimCutStrategy,
    selected: TrimSubMode,
    onSelect: (TrimSubMode) -> Unit
) {
    val extColors = LocalExtendedColors.current
    ConfigSectionCard(
        title = "Kesim Modu",
        subtitle = "Kayıpsız kesimler -noaccurate_seek + -avoid_negative_ts make_zero kullanır"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TrimSubMode.entries.forEach { mode ->
                val isDisabled = cutStrategy == TrimCutStrategy.PRECISE_REENCODE && mode.requiresLosslessKeyframes
                val isSelected = !isDisabled && mode == selected

                val containerColor = when {
                    isDisabled -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.68f)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
                val borderColor = when {
                    isDisabled -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> extColors.subtleBorder
                }
                val titleColor = when {
                    isDisabled -> MaterialTheme.colorScheme.error
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val subtitleColor = when {
                    isDisabled -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = !isDisabled) { onSelect(mode) }
                        .testTag("trim_submode_${mode.number}"),
                    shape = MaterialTheme.shapes.small,
                    color = containerColor,
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = borderColor
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${mode.number})",
                            style = MonoLabelStyle.copy(fontWeight = FontWeight.Bold),
                            color = if (isDisabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = titleColor
                            )
                            Text(
                                text = mode.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = subtitleColor
                            )
                        }
                        if (isDisabled) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = "Hassas kesimde seçilemez",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        } else if (isSelected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
