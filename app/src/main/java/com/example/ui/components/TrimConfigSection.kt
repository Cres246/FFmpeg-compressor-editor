package com.example.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PreviewQuality
import com.example.data.VideoFrameScrubber
import com.example.domain.FfmpegMath
import com.example.domain.LocalAppStrings
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.TrimCutStrategy
import com.example.domain.TrimSubMode
import com.example.domain.localizedSubtitle
import com.example.domain.localizedTitle
import com.example.ui.MainViewModel
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoLabelStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

enum class ActiveTrimThumb { START, END }

/**
 * Shared 120Hz slider drag state between [TrimTimelineRangeBar], [TrimTimeAndKeyframeSection],
 * and [TrimVideoPreviewCard]. Keeps handles, summary timestamps, and thumbnails fluid at 120Hz
 * while throttling heavy ViewModel / FFmpeg preview recalculations to ~11Hz during dragging.
 */
@Stable
class TrimScrubState {
    var draggingThumb by mutableStateOf<ActiveTrimThumb?>(null)
        private set
    var liveStartSec by mutableStateOf<Double?>(null)
        private set
    var liveEndSec by mutableStateOf<Double?>(null)
        private set

    val isScrubbing: Boolean
        get() = draggingThumb != null

    fun updateDrag(thumb: ActiveTrimThumb, startSec: Double, endSec: Double) {
        draggingThumb = thumb
        liveStartSec = startSec
        liveEndSec = endSec
    }

    fun endDrag() {
        draggingThumb = null
        liveStartSec = null
        liveEndSec = null
    }
}

private class LastValidPreviewSec(var start: Double, var end: Double)

private class DisplayedFrameTracker {
    var sec: Double? = null
    var isCrisp: Boolean = false

    fun reset() {
        sec = null
        isCrisp = false
    }
}

private const val SLIDER_VIEWMODEL_THROTTLE_MS = 90L
private const val SCRUB_HOLD_REFINE_DELAY_MS = 220L
private const val SETTLE_REFINE_DELAY_MS = 65L

/**
 * Top preview card on Trim screen: two side-by-side square frame thumbnails showing the exact
 * selected start & end frames, plus a compact play button below to open the video in any player.
 */
@Composable
fun TrimVideoPreviewCard(
    media: MediaInfo,
    config: ModeConfigState,
    onPlayVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrubState: TrimScrubState? = null,
    isActiveStage: Boolean = true
) {
    val context = LocalContext.current
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val (effStart, effEnd, _) = FfmpegMath.resolveTrimRange(config, media.durationSec)

    val parsedStartSec = effStart?.let { FfmpegMath.parseTimestampToSeconds(it) }
    val parsedEndSec = effEnd?.let { FfmpegMath.parseTimestampToSeconds(it) }

    val isEndBeyondDuration = media.durationSec != null &&
        parsedEndSec != null &&
        parsedEndSec > media.durationSec + 0.05

    val defaultEndSec = (media.durationSec?.let { (it - 0.25).coerceAtLeast(0.0) } ?: 0.0)
    val lastValid = remember(media.uri, media.durationSec) {
        LastValidPreviewSec(start = 0.0, end = defaultEndSec)
    }

    val committedStartSec = when {
        effStart == null -> 0.0
        parsedStartSec != null -> parsedStartSec
        else -> lastValid.start
    }
    lastValid.start = committedStartSec

    val committedEndSec = when {
        effEnd == null -> defaultEndSec
        parsedEndSec != null -> parsedEndSec
        else -> lastValid.end
    }
    lastValid.end = committedEndSec

    val isScrubbingStart = scrubState?.draggingThumb == ActiveTrimThumb.START
    val isScrubbingEnd = scrubState?.draggingThumb == ActiveTrimThumb.END
    val liveStartOverride = if (isScrubbingStart) scrubState.liveStartSec else null
    val liveEndOverride = if (isScrubbingEnd) scrubState.liveEndSec else null

    val effectiveStartSec = liveStartOverride ?: committedStartSec
    val effectiveEndSec = liveEndOverride ?: committedEndSec
    val effectiveEndBeyond = if (liveEndOverride != null) false else isEndBeyondDuration

    val startLabel = remember(effStart, liveStartOverride, effectiveStartSec, strings) {
        if (liveStartOverride == null && effStart == null) "00:00.0"
        else FfmpegMath.formatKeyframeTime(effectiveStartSec, strings)
    }
    val endLabel = remember(effEnd, liveEndOverride, effectiveEndSec, media.durationSec, strings) {
        when {
            liveEndOverride != null -> FfmpegMath.formatKeyframeTime(effectiveEndSec, strings)
            effEnd != null -> FfmpegMath.formatKeyframeTime(effectiveEndSec, strings)
            media.durationSec != null -> FfmpegMath.formatKeyframeTime(media.durationSec, strings)
            else -> strings.trimEndPlaceholder
        }
    }

    var startBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    var startLoading by remember(media.uri) { mutableStateOf(media.hasVideo) }
    var endBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    var endLoading by remember(media.uri) { mutableStateOf(media.hasVideo) }

    val startTracker = remember(media.uri) { DisplayedFrameTracker() }
    val endTracker = remember(media.uri) { DisplayedFrameTracker() }

    val latestStartSec = rememberUpdatedState(effectiveStartSec)
    val latestEndSec = rememberUpdatedState(effectiveEndSec)
    val latestScrubbingStart = rememberUpdatedState(isScrubbingStart)
    val latestScrubbingEnd = rememberUpdatedState(isScrubbingEnd)
    val latestEndBeyond = rememberUpdatedState(effectiveEndBeyond)

    // Persistent hardware-backed frame scrubber bound strictly to active Config stage so
    // MediaMetadataRetriever & VPU decoder sessions are freed immediately on Back or Start.
    val scrubber = remember(media.uri, media.durationSec, isActiveStage) {
        if (media.hasVideo && isActiveStage) {
            VideoFrameScrubber(
                context = context,
                uri = media.uri,
                durationSec = media.durationSec
            )
        } else {
            null
        }
    }
    DisposableEffect(scrubber) {
        onDispose { scrubber?.close() }
    }

    // Silent background timeline pre-warming (24 low-res RGB_565 keyframes across the timeline).
    LaunchedEffect(scrubber) {
        scrubber?.prewarmTimeline()
    }

    // --- START THUMBNAIL PIPELINE ---
    // Stage 0: 0ms RAM lookup on every 120Hz scrub tick for instant pre-warmed / cached hits.
    LaunchedEffect(scrubber, effectiveStartSec) {
        val activeScrubber = scrubber ?: return@LaunchedEffect
        val cached = activeScrubber.peekInstantFrame(
            timeSec = effectiveStartSec,
            allowNearbyFallback = latestScrubbingStart.value
        )
        if (cached != null) {
            val prevSec = startTracker.sec
            val isCloser = prevSec == null ||
                abs(cached.timestampSec - effectiveStartSec) < abs(prevSec - effectiveStartSec) - 0.01 ||
                (abs(cached.timestampSec - effectiveStartSec) < 0.05 && cached.isCrisp && !startTracker.isCrisp)
            if (isCloser || startBitmap == null) {
                startBitmap = cached.bitmap
                startTracker.sec = cached.timestampSec
                startTracker.isCrisp = cached.isCrisp
                startLoading = false
            }
        }
    }

    // Stage 1: Conflated fast keyframe decode (256px RGB_565 OPTION_CLOSEST_SYNC) — never starves at 120Hz.
    LaunchedEffect(scrubber) {
        val activeScrubber = scrubber ?: return@LaunchedEffect
        snapshotFlow { latestStartSec.value }
            .distinctUntilChanged()
            .conflate()
            .collect { sec ->
                if (activeScrubber.hasCrispFrame(sec)) {
                    activeScrubber.peekInstantFrame(sec, allowNearbyFallback = false)?.let { cached ->
                        startBitmap = cached.bitmap
                        startTracker.sec = cached.timestampSec
                        startTracker.isCrisp = true
                    }
                    startLoading = false
                    return@collect
                }
                if (latestScrubbingStart.value && activeScrubber.hasExactScrubFrame(sec)) {
                    activeScrubber.peekInstantFrame(sec, allowNearbyFallback = false)?.let { cached ->
                        val prevSec = startTracker.sec
                        if (startBitmap == null || prevSec == null ||
                            abs(cached.timestampSec - sec) < abs(prevSec - sec) ||
                            !startTracker.isCrisp
                        ) {
                            startBitmap = cached.bitmap
                            startTracker.sec = cached.timestampSec
                            startTracker.isCrisp = cached.isCrisp
                        }
                    }
                    startLoading = false
                    return@collect
                }

                if (startBitmap == null) {
                    startLoading = true
                }
                val fastFrame = activeScrubber.extractFrame(sec, PreviewQuality.FAST_SCRUB)
                if (fastFrame != null) {
                    val curTarget = latestStartSec.value
                    val prevSec = startTracker.sec
                    val shouldApply = startBitmap == null ||
                        prevSec == null ||
                        abs(sec - curTarget) < abs(prevSec - curTarget) - 0.01 ||
                        (abs(sec - curTarget) <= 0.05 && !startTracker.isCrisp)
                    if (shouldApply) {
                        startBitmap = fastFrame
                        startTracker.sec = sec
                        startTracker.isCrisp = false
                    }
                }
                if (startBitmap != null || fastFrame == null) {
                    startLoading = false
                }
            }
    }

    // Stage 2: Exact frame-accurate refinement (480px ARGB_8888 OPTION_CLOSEST) on pause or release.
    LaunchedEffect(scrubber, effectiveStartSec, isScrubbingStart) {
        val activeScrubber = scrubber ?: return@LaunchedEffect
        val targetSec = effectiveStartSec
        if (activeScrubber.hasCrispFrame(targetSec)) {
            activeScrubber.peekInstantFrame(targetSec, allowNearbyFallback = false)?.let { cached ->
                startBitmap = cached.bitmap
                startTracker.sec = cached.timestampSec
                startTracker.isCrisp = true
            }
            startLoading = false
            return@LaunchedEffect
        }

        delay(if (isScrubbingStart) SCRUB_HOLD_REFINE_DELAY_MS else SETTLE_REFINE_DELAY_MS)

        val crispFrame = activeScrubber.extractFrame(targetSec, PreviewQuality.EXACT_CRISP)
        if (crispFrame != null && abs(latestStartSec.value - targetSec) < 0.05) {
            startBitmap = crispFrame
            startTracker.sec = targetSec
            startTracker.isCrisp = true
        }
        startLoading = false
    }

    // --- END THUMBNAIL PIPELINE ---
    // Stage 0: 0ms RAM lookup on every 120Hz scrub tick.
    LaunchedEffect(scrubber, effectiveEndSec, effectiveEndBeyond) {
        if (effectiveEndBeyond) {
            endBitmap = null
            endTracker.reset()
            endLoading = false
            return@LaunchedEffect
        }
        val activeScrubber = scrubber ?: return@LaunchedEffect
        val cached = activeScrubber.peekInstantFrame(
            timeSec = effectiveEndSec,
            allowNearbyFallback = latestScrubbingEnd.value
        )
        if (cached != null) {
            val prevSec = endTracker.sec
            val isCloser = prevSec == null ||
                abs(cached.timestampSec - effectiveEndSec) < abs(prevSec - effectiveEndSec) - 0.01 ||
                (abs(cached.timestampSec - effectiveEndSec) < 0.05 && cached.isCrisp && !endTracker.isCrisp)
            if (isCloser || endBitmap == null) {
                endBitmap = cached.bitmap
                endTracker.sec = cached.timestampSec
                endTracker.isCrisp = cached.isCrisp
                endLoading = false
            }
        }
    }

    // Stage 1: Conflated fast keyframe decode for End thumbnail.
    LaunchedEffect(scrubber) {
        val activeScrubber = scrubber ?: return@LaunchedEffect
        snapshotFlow { latestEndSec.value to latestEndBeyond.value }
            .distinctUntilChanged()
            .conflate()
            .collect { (sec, beyond) ->
                if (beyond) {
                    endBitmap = null
                    endTracker.reset()
                    endLoading = false
                    return@collect
                }
                if (activeScrubber.hasCrispFrame(sec)) {
                    activeScrubber.peekInstantFrame(sec, allowNearbyFallback = false)?.let { cached ->
                        if (!latestEndBeyond.value) {
                            endBitmap = cached.bitmap
                            endTracker.sec = cached.timestampSec
                            endTracker.isCrisp = true
                        }
                    }
                    endLoading = false
                    return@collect
                }
                if (latestScrubbingEnd.value && activeScrubber.hasExactScrubFrame(sec)) {
                    activeScrubber.peekInstantFrame(sec, allowNearbyFallback = false)?.let { cached ->
                        val prevSec = endTracker.sec
                        if (!latestEndBeyond.value && (endBitmap == null || prevSec == null ||
                                abs(cached.timestampSec - sec) < abs(prevSec - sec) ||
                                !endTracker.isCrisp)
                        ) {
                            endBitmap = cached.bitmap
                            endTracker.sec = cached.timestampSec
                            endTracker.isCrisp = cached.isCrisp
                        }
                    }
                    endLoading = false
                    return@collect
                }

                if (endBitmap == null) {
                    endLoading = true
                }
                val fastFrame = activeScrubber.extractFrame(sec, PreviewQuality.FAST_SCRUB)
                if (!latestEndBeyond.value && fastFrame != null) {
                    val curTarget = latestEndSec.value
                    val prevSec = endTracker.sec
                    val shouldApply = endBitmap == null ||
                        prevSec == null ||
                        abs(sec - curTarget) < abs(prevSec - curTarget) - 0.01 ||
                        (abs(sec - curTarget) <= 0.05 && !endTracker.isCrisp)
                    if (shouldApply) {
                        endBitmap = fastFrame
                        endTracker.sec = sec
                        endTracker.isCrisp = false
                    }
                }
                if (endBitmap != null || fastFrame == null || latestEndBeyond.value) {
                    endLoading = false
                }
            }
    }

    // Stage 2: Exact frame-accurate refinement for End thumbnail on pause or release.
    LaunchedEffect(scrubber, effectiveEndSec, isScrubbingEnd, effectiveEndBeyond) {
        if (effectiveEndBeyond) {
            endLoading = false
            return@LaunchedEffect
        }
        val activeScrubber = scrubber ?: return@LaunchedEffect
        val targetSec = effectiveEndSec
        if (activeScrubber.hasCrispFrame(targetSec)) {
            activeScrubber.peekInstantFrame(targetSec, allowNearbyFallback = false)?.let { cached ->
                if (!latestEndBeyond.value) {
                    endBitmap = cached.bitmap
                    endTracker.sec = cached.timestampSec
                    endTracker.isCrisp = true
                }
            }
            endLoading = false
            return@LaunchedEffect
        }

        delay(if (isScrubbingEnd) SCRUB_HOLD_REFINE_DELAY_MS else SETTLE_REFINE_DELAY_MS)

        val crispFrame = activeScrubber.extractFrame(targetSec, PreviewQuality.EXACT_CRISP)
        if (!latestEndBeyond.value && crispFrame != null && abs(latestEndSec.value - targetSec) < 0.05) {
            endBitmap = crispFrame
            endTracker.sec = targetSec
            endTracker.isCrisp = true
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
                    tag = strings.previewStartTag,
                    timeText = startLabel,
                    bitmap = startBitmap,
                    isLoading = startLoading,
                    isOutOfBoundsBlack = false,
                    onClick = onPlayVideoClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_preview_start_frame")
                )
                SquareFramePreviewBox(
                    tag = strings.previewEndTag,
                    timeText = endLabel,
                    bitmap = if (effectiveEndBeyond) null else endBitmap,
                    isLoading = !effectiveEndBeyond && endLoading,
                    isOutOfBoundsBlack = effectiveEndBeyond,
                    onClick = onPlayVideoClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_preview_end_frame")
                )
            }

            if (effectiveEndBeyond && media.durationSec != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trim_end_exceeds_warning"),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = strings.trimEndExceedsDurationWarning(
                                FfmpegMath.formatKeyframeTime(media.durationSec, strings)
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
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
                    text = strings.playVideoButton,
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
    isOutOfBoundsBlack: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val shape = RoundedCornerShape(12.dp)
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (isOutOfBoundsBlack) Color.Black else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isOutOfBoundsBlack) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = strings.trimEndOutOfBoundsBadge,
                    tint = Color(0xFFFF7B6B),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = strings.trimEndOutOfBoundsBadge,
                    style = MonoLabelStyle.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    ),
                    color = Color(0xFFFFB4AB),
                    maxLines = 1
                )
            }
        } else if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = strings.previewFrameCd(tag, timeText),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!isLoading) {
            Icon(
                imageVector = Icons.Rounded.Videocam,
                contentDescription = strings.previewFrameFallbackCd(tag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp)
            )
        }

        if (isLoading && !isOutOfBoundsBlack) {
            if (bitmap != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(15.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.04f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
                    color = if (isOutOfBoundsBlack) Color(0xFFFFB4AB) else Color.White.copy(alpha = 0.88f),
                    maxLines = 1
                )
                Text(
                    text = timeText,
                    style = MonoLabelStyle.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isOutOfBoundsBlack) Color(0xFFFF7B6B) else Color.White,
                    maxLines = 1
                )
            }
        }

        // Subtle inner border
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(
                width = if (isOutOfBoundsBlack) 1.5.dp else 1.dp,
                color = if (isOutOfBoundsBlack) MaterialTheme.colorScheme.error else extColors.subtleBorder
            )
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
    viewModel: MainViewModel,
    scrubState: TrimScrubState? = null
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val (effStart, effEnd, clipDur) = FfmpegMath.resolveTrimRange(config, media.durationSec)
    val parsedStartSec = effStart?.let { FfmpegMath.parseTimestampToSeconds(it) }
    val parsedEndSec = effEnd?.let { FfmpegMath.parseTimestampToSeconds(it) }
    val isEndBeyondDuration = media.durationSec != null &&
        parsedEndSec != null &&
        parsedEndSec > media.durationSec + 0.05

    val liveStart = scrubState?.liveStartSec
    val liveEnd = scrubState?.liveEndSec
    val isActivelyScrubbing = scrubState?.isScrubbing == true && liveStart != null && liveEnd != null

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
                        val startLabel = if (isActivelyScrubbing) {
                            FfmpegMath.formatKeyframeTime(liveStart, strings)
                        } else {
                            effStart?.let {
                                val parsed = FfmpegMath.parseTimestampToSeconds(it)
                                if (parsed != null && !it.contains(':')) FfmpegMath.formatKeyframeTime(parsed, strings) else it
                            } ?: strings.trimStartSummaryDefault
                        }
                        val endLabel = if (isActivelyScrubbing) {
                            FfmpegMath.formatKeyframeTime(liveEnd, strings)
                        } else {
                            effEnd?.let {
                                val parsed = FfmpegMath.parseTimestampToSeconds(it)
                                if (parsed != null && !it.contains(':')) FfmpegMath.formatKeyframeTime(parsed, strings) else it
                            } ?: strings.trimEndPlaceholder
                        }
                        Text(
                            text = "$startLabel  →  $endLabel",
                            style = MonoLabelStyle.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    val displayClipDur = if (isActivelyScrubbing) {
                        (liveEnd - liveStart).coerceAtLeast(0.0)
                    } else {
                        clipDur
                    }
                    if (displayClipDur != null && (isActivelyScrubbing || !isEndBeyondDuration)) {
                        Text(
                            text = FfmpegMath.formatDurationShort(displayClipDur, strings),
                            style = MonoLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Interactive dark-brown horizontal range slider directly above timestamp inputs
            val totalDur = media.durationSec
            if (totalDur != null && totalDur > 0.0) {
                Spacer(Modifier.height(6.dp))
                val visualStart = (parsedStartSec ?: 0.0).coerceIn(0.0, totalDur)
                val visualEnd = (parsedEndSec ?: totalDur).coerceIn(visualStart, totalDur)
                TrimTimelineRangeBar(
                    durationSec = totalDur,
                    startSec = visualStart,
                    endSec = visualEnd,
                    scrubState = scrubState,
                    onRangeChanged = { newStart, newEnd, activeThumb, isDragFinished ->
                        viewModel.updateTrimRangeFromSlider(
                            startSec = newStart,
                            endSec = newEnd,
                            updateStart = activeThumb == ActiveTrimThumb.START,
                            updateEnd = activeThumb == ActiveTrimThumb.END,
                            isDragFinished = isDragFinished
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trim_range_slider")
                )
                Spacer(Modifier.height(4.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }

            // Start & End time inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = config.startTimeInput,
                    onValueChange = viewModel::updateStartTime,
                    label = { Text(strings.trimStartLabel) },
                    placeholder = {
                        Text(
                            text = strings.trimStartPlaceholder,
                            style = MonoLabelStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_start_input")
                )
                OutlinedTextField(
                    value = config.endTimeInput,
                    onValueChange = viewModel::updateEndTime,
                    label = { Text(strings.trimEndLabel) },
                    placeholder = { Text(strings.trimEndPlaceholder, style = MonoLabelStyle) },
                    isError = isEndBeyondDuration,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trim_end_input")
                )
            }

            if (isEndBeyondDuration && totalDur != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = strings.trimEndExceedsDurationWarning(
                            FfmpegMath.formatKeyframeTime(totalDur, strings)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val startScan = config.trimScan.startScan
            val endScan = config.trimScan.endScan
            val showKeyframeCard = config.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME &&
                (startScan != null || endScan != null || (config.trimScan.panelExpanded && config.trimScan.isScanning))
            if (showKeyframeCard) {
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
                        if (startScan == null && endScan == null && config.trimScan.isScanning) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = strings.scanningKeyframes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (startScan != null) {
                            val activeStartKf = config.selectedStartKfSec ?: startScan.prevKf
                            KeyframeGridBlock(
                                label = strings.startKeyframesLabel,
                                keyframes = FfmpegMath.selectCenteredKeyframes(
                                    keyframes = startScan.nearbyKeyframes,
                                    targetSec = startScan.targetSec,
                                    maxCount = 12
                                ),
                                selectedKf = activeStartKf,
                                isRefreshing = config.trimScan.isScanning,
                                onSelectKeyframe = viewModel::applyNearbyKeyframeToStart
                            )
                        }
                        if (endScan != null) {
                            val activeEndKf = config.selectedEndKfSec ?: endScan.nextKf
                            KeyframeGridBlock(
                                label = strings.endKeyframesLabel,
                                keyframes = FfmpegMath.selectCenteredKeyframes(
                                    keyframes = endScan.nearbyKeyframes,
                                    targetSec = endScan.targetSec,
                                    maxCount = 12
                                ),
                                selectedKf = activeEndKf,
                                isRefreshing = startScan == null && config.trimScan.isScanning,
                                onSelectKeyframe = viewModel::applyNearbyKeyframeToEnd
                            )
                        }
                    }
                }
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
                            text = strings.trimLosslessInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = extColors.onLosslessContainer
                        )
                    }
                }

                val hasStartInput = config.startTimeInput.trim().isNotEmpty()
                val hasEndInput = config.endTimeInput.trim().isNotEmpty()
                val needsScan = (hasStartInput && config.trimScan.startScan == null) ||
                    (hasEndInput && config.trimScan.endScan == null)
                val showScanButton = (!config.trimScan.panelExpanded && (config.trimScan.isScanning || needsScan)) ||
                    (config.trimScan.panelExpanded && config.trimScan.error != null)

                if (showScanButton) {
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
                            Text(strings.scanningKeyframes)
                        } else {
                            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.showKeyframesButton)
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
                            text = strings.trimPreciseInfo,
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
    val strings = LocalAppStrings.current
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = strategy.localizedTitle(strings),
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
                text = strategy.localizedSubtitle(strings),
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
    isRefreshing: Boolean = false,
    onSelectKeyframe: (Double) -> Unit
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val rows = keyframes.chunked(4)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.6.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                text = strings.noNearbyKeyframes,
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
                                        contentDescription = strings.selectedKeyframeCd,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                }
                                Text(
                                    text = FfmpegMath.formatKeyframeTime(kf, strings),
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

/**
 * Sleek dark-brown horizontal range bar with circular drag handles on both ends.
 * Updates handles and live frame previews at 120Hz via [scrubState] while throttling heavier
 * ViewModel / FFmpeg command recalculations and preserving LazyColumn vertical scroll.
 */
@Composable
private fun TrimTimelineRangeBar(
    durationSec: Double,
    startSec: Double,
    endSec: Double,
    scrubState: TrimScrubState?,
    onRangeChanged: (Double, Double, ActiveTrimThumb, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTrackColor = MaterialTheme.colorScheme.primary // CelloBrown (#562812)
    val inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val ringColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val thumbPaddingPx = with(density) { 12.dp.toPx() }

    val latestStart = rememberUpdatedState(startSec)
    val latestEnd = rememberUpdatedState(endSec)
    val latestOnRangeChanged = rememberUpdatedState(onRangeChanged)
    val latestScrubState = rememberUpdatedState(scrubState)

    var localDraggingThumb by remember { mutableStateOf<ActiveTrimThumb?>(null) }

    val safeDuration = durationSec.coerceAtLeast(0.1)

    Box(
        modifier = modifier
            .height(48.dp)
            .pointerInput(safeDuration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val trackWidth = (size.width - thumbPaddingPx * 2f).coerceAtLeast(1f)
                    var curStartSec = (latestScrubState.value?.liveStartSec ?: latestStart.value)
                        .coerceIn(0.0, safeDuration)
                    var curEndSec = (latestScrubState.value?.liveEndSec ?: latestEnd.value)
                        .coerceIn(curStartSec, safeDuration)

                    val startX = thumbPaddingPx + (curStartSec / safeDuration).toFloat().coerceIn(0f, 1f) * trackWidth
                    val endX = thumbPaddingPx + (curEndSec / safeDuration).toFloat().coerceIn(0f, 1f) * trackWidth

                    val distStart = abs(down.position.x - startX)
                    val distEnd = abs(down.position.x - endX)
                    var chosenThumb: ActiveTrimThumb? = when {
                        abs(endX - startX) < 12f -> null // resolve by initial drag direction
                        distStart <= distEnd -> ActiveTrimThumb.START
                        else -> ActiveTrimThumb.END
                    }

                    val minGapSec = minOf(0.5, safeDuration * 0.02).coerceAtLeast(0.1)
                    var lastCommitMs = 0L
                    var didDrag = false

                    fun applyPositionX(posX: Float, thumb: ActiveTrimThumb) {
                        val frac = ((posX - thumbPaddingPx) / trackWidth).coerceIn(0f, 1f)
                        val targetSec = frac * safeDuration
                        if (thumb == ActiveTrimThumb.START) {
                            curStartSec = targetSec.coerceIn(0.0, (curEndSec - minGapSec).coerceAtLeast(0.0))
                        } else {
                            curEndSec = targetSec.coerceIn((curStartSec + minGapSec).coerceAtMost(safeDuration), safeDuration)
                        }
                        didDrag = true
                        latestScrubState.value?.updateDrag(thumb, curStartSec, curEndSec)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastCommitMs >= SLIDER_VIEWMODEL_THROTTLE_MS) {
                            lastCommitMs = now
                            latestOnRangeChanged.value(curStartSec, curEndSec, thumb, false)
                        }
                    }

                    val dragStart = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        val resolved = chosenThumb ?: if (overSlop < 0f) ActiveTrimThumb.START else ActiveTrimThumb.END
                        chosenThumb = resolved
                        localDraggingThumb = resolved
                        change.consume()
                        applyPositionX(change.position.x, resolved)
                    }

                    if (dragStart != null) {
                        val lockedThumb = chosenThumb ?: ActiveTrimThumb.END
                        try {
                            horizontalDrag(dragStart.id) { change ->
                                val delta = change.positionChange().x
                                if (delta != 0f) {
                                    change.consume()
                                    applyPositionX(change.position.x, lockedThumb)
                                }
                            }
                        } finally {
                            if (didDrag) {
                                // Commit final exact range to ViewModel BEFORE clearing live scrub state
                                // so thumbnails and text fields never jump back for a single frame.
                                latestOnRangeChanged.value(curStartSec, curEndSec, lockedThumb, true)
                            }
                            latestScrubState.value?.endDrag()
                            localDraggingThumb = null
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val drawStartSec = scrubState?.liveStartSec ?: startSec
            val drawEndSec = scrubState?.liveEndSec ?: endSec
            val activeThumb = scrubState?.draggingThumb ?: localDraggingThumb

            val startFraction = (drawStartSec / safeDuration).toFloat().coerceIn(0f, 1f)
            val endFraction = (drawEndSec / safeDuration).toFloat().coerceIn(startFraction, 1f)

            val centerY = size.height / 2f
            val trackStart = thumbPaddingPx
            val trackEnd = (size.width - thumbPaddingPx).coerceAtLeast(trackStart + 1f)
            val trackSpan = trackEnd - trackStart

            val startX = trackStart + startFraction * trackSpan
            val endX = trackStart + endFraction * trackSpan

            // Full thin background track
            drawLine(
                color = inactiveTrackColor,
                start = Offset(trackStart, centerY),
                end = Offset(trackEnd, centerY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Active thin dark-brown trim line between the two circular handles
            drawLine(
                color = activeTrackColor,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = 3.2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Left circular thumb
            val startDragging = activeThumb == ActiveTrimThumb.START
            val startRadius = (if (startDragging) 10.5.dp else 9.dp).toPx()
            if (startDragging) {
                drawCircle(
                    color = activeTrackColor.copy(alpha = 0.18f),
                    radius = 18.dp.toPx(),
                    center = Offset(startX, centerY)
                )
            }
            drawCircle(
                color = ringColor,
                radius = startRadius + 2.dp.toPx(),
                center = Offset(startX, centerY)
            )
            drawCircle(
                color = activeTrackColor,
                radius = startRadius,
                center = Offset(startX, centerY)
            )

            // Right circular thumb
            val endDragging = activeThumb == ActiveTrimThumb.END
            val endRadius = (if (endDragging) 10.5.dp else 9.dp).toPx()
            if (endDragging) {
                drawCircle(
                    color = activeTrackColor.copy(alpha = 0.18f),
                    radius = 18.dp.toPx(),
                    center = Offset(endX, centerY)
                )
            }
            drawCircle(
                color = ringColor,
                radius = endRadius + 2.dp.toPx(),
                center = Offset(endX, centerY)
            )
            drawCircle(
                color = activeTrackColor,
                radius = endRadius,
                center = Offset(endX, centerY)
            )
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
    val strings = LocalAppStrings.current
    ConfigSectionCard(
        title = strings.trimModeTitle,
        subtitle = strings.trimModeSub
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TrimSubMode.entries.forEach { mode ->
                val isDisabled = cutStrategy == TrimCutStrategy.PRECISE_REENCODE && mode.requiresLosslessKeyframes
                val isGreenLossless = !isDisabled && mode.requiresLosslessKeyframes
                val isSelected = !isDisabled && mode == selected

                val containerColor = when {
                    isDisabled -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.68f)
                    isGreenLossless && isSelected -> extColors.losslessContainer
                    isGreenLossless -> extColors.losslessContainer.copy(alpha = 0.44f)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
                val borderColor = when {
                    isDisabled -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                    isGreenLossless && isSelected -> extColors.lossless
                    isGreenLossless -> extColors.lossless.copy(alpha = 0.45f)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> extColors.subtleBorder
                }
                val numberColor = when {
                    isDisabled -> MaterialTheme.colorScheme.error
                    isGreenLossless -> extColors.lossless
                    else -> MaterialTheme.colorScheme.primary
                }
                val titleColor = when {
                    isDisabled -> MaterialTheme.colorScheme.error
                    isGreenLossless && isSelected -> extColors.onLosslessContainer
                    isGreenLossless -> extColors.lossless
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val subtitleColor = when {
                    isDisabled -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f)
                    isGreenLossless -> extColors.onLosslessContainer.copy(alpha = 0.82f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val checkTint = if (isGreenLossless) extColors.lossless else MaterialTheme.colorScheme.primary

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = !isDisabled) { onSelect(mode) }
                        .testTag("trim_submode_${mode.number}"),
                    shape = MaterialTheme.shapes.small,
                    color = containerColor,
                    border = BorderStroke(
                        width = if (isSelected) 1.6.dp else 1.dp,
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
                            color = numberColor
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.localizedTitle(strings),
                                style = MaterialTheme.typography.titleSmall,
                                color = titleColor
                            )
                            Text(
                                text = mode.localizedSubtitle(strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = subtitleColor
                            )
                        }
                        if (isDisabled) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = strings.trimModeDisabledCd,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        } else if (isSelected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = checkTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
