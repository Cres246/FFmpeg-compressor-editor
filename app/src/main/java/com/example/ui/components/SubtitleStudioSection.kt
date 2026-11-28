package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.DeviceCodecInventory
import com.example.data.PreviewQuality
import com.example.data.VideoFrameScrubber
import com.example.domain.EncoderCapabilities
import com.example.domain.FfmpegMath
import com.example.domain.LocalAppStrings
import com.example.domain.Media3CompatibilityAdvisor
import com.example.domain.Media3IssueKind
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.SubtitleCue
import com.example.domain.SubtitleEmbedMode
import com.example.domain.SubtitleFontOption
import com.example.domain.SubtitleFontSizeOption
import com.example.domain.SubtitleParser
import com.example.domain.SubtitleSoftsubContainer
import com.example.domain.SubtitleTrackInfo
import com.example.domain.localizedLabel
import com.example.domain.localizedTag
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoLabelStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Interactive Subtitle Studio:
 * - Large horizontal 16:9 live video screen powered by Jetpack Media3 [ExoPlayer] with full audio
 *   and in-place Play/Pause control (never launches an external player).
 * - Automatic seamless fallback to a single large horizontal 120Hz [VideoFrameScrubber] silent
 *   frame view whenever the source codec/container is unsupported by Media3.
 * - Live WYSIWYG subtitle overlay rendered in the exact bundled full-UTF-8 font and size.
 * - Trim-style dark-brown 120Hz timeline bar + editable timestamp & active cue range boxes.
 * - Infinite right-to-left virtualized horizontal subtitle ribbon with momentum fling physics,
 *   real-time 4-way synchronization (ribbon <-> bar <-> time box <-> video), and direct inline
 *   tap-to-edit box immediately underneath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStudioSection(
    media: MediaInfo,
    config: ModeConfigState,
    isLoadingSubtitle: Boolean,
    isActiveStage: Boolean,
    onPickSubtitleFile: () -> Unit,
    onLoadEmbeddedTrack: (SubtitleTrackInfo) -> Unit,
    onUpdateCue: (cueId: Long, newText: String, newStartSec: Double?, newEndSec: Double?) -> Unit,
    onAddCueAt: (timeSec: Double) -> Long,
    onDeleteCue: (cueId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val extColors = LocalExtendedColors.current

    val totalDurationSec = remember(media.durationSec, config.subtitleCues) {
        val mediaDur = media.durationSec?.takeIf { it.isFinite() && it > 0.2 }
        val lastCueEnd = config.subtitleCues.maxOfOrNull { it.endSec }
        maxOf(mediaDur ?: 0.0, lastCueEnd ?: 0.0, 10.0)
    }

    val initialUnsupported = remember(media.uri, media.videoCodec, media.extension) {
        val compat = Media3CompatibilityAdvisor.diagnose(
            media = media,
            config = config,
            device = DeviceCodecInventory.queryCurrentDevice()
        )
        compat.issueKind == Media3IssueKind.VIDEO_INCOMPATIBLE ||
            compat.issueKind == Media3IssueKind.VIDEO_AND_AUDIO_INCOMPATIBLE ||
            media.extension.lowercase() in Media3CompatibilityAdvisor.UNSUPPORTED_CONTAINERS
    }

    var useFrameFallback by remember(media.uri) { mutableStateOf(initialUnsupported) }
    var currentTimeSec by remember(media.uri) { mutableDoubleStateOf(0.0) }
    var isPlaying by remember(media.uri) { mutableStateOf(false) }
    var isScrubbing by remember(media.uri) { mutableStateOf(false) }
    var showPlayOverlay by remember(media.uri) { mutableStateOf(true) }
    var selectedCueId by remember(media.uri) { mutableStateOf<Long?>(null) }
    var videoAspect by remember(media.uri) {
        val w = media.width ?: 16
        val h = media.height ?: 9
        mutableFloatStateOf((w.toFloat() / h.toFloat().coerceAtLeast(1f)).coerceIn(0.45f, 2.5f))
    }

    // Media3 ExoPlayer lifecycle strictly bound to isActiveStage (Redline #15: zero lingering VPU)
    var exoPlayer by remember(media.uri) { mutableStateOf<ExoPlayer?>(null) }
    var textureViewRef by remember(media.uri) { mutableStateOf<TextureView?>(null) }
    var lastExoSeekWallMs by remember(media.uri) { mutableLongStateOf(0L) }

    DisposableEffect(media.uri, isActiveStage, useFrameFallback) {
        if (!isActiveStage || useFrameFallback) {
            exoPlayer = null
            isPlaying = false
            onDispose {}
        } else {
            val player = runCatching {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(media.uri))
                    prepare()
                    playWhenReady = false
                    if (currentTimeSec > 0.05) {
                        seekTo((currentTimeSec * 1000.0).toLong())
                    }
                }
            }.getOrNull()

            if (player == null) {
                useFrameFallback = true
                onDispose {}
            } else {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        isPlaying = false
                        useFrameFallback = true
                    }

                    override fun onIsPlayingChanged(playingNow: Boolean) {
                        isPlaying = playingNow
                        if (!playingNow) {
                            showPlayOverlay = true
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            isPlaying = false
                            showPlayOverlay = true
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            videoAspect = (videoSize.width.toFloat() / videoSize.height.toFloat()).coerceIn(0.45f, 2.5f)
                        }
                    }
                }
                player.addListener(listener)
                textureViewRef?.let { tv -> runCatching { player.setVideoTextureView(tv) } }
                exoPlayer = player
                onDispose {
                    runCatching { player.removeListener(listener) }
                    textureViewRef?.let { tv -> runCatching { player.clearVideoTextureView(tv) } }
                    runCatching { player.release() }
                    exoPlayer = null
                    isPlaying = false
                }
            }
        }
    }

    // Auto-hide center play button cleanly 1.4s after playback begins
    LaunchedEffect(isPlaying, showPlayOverlay) {
        if (isPlaying && showPlayOverlay) {
            delay(1_400L)
            if (isPlaying) showPlayOverlay = false
        }
    }

    // 60-120Hz live position sync while Media3 ExoPlayer is playing -> ribbon, bar, time, and overlay glide in lockstep
    LaunchedEffect(exoPlayer, isPlaying, isScrubbing) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (isActive && isPlaying && !isScrubbing) {
            val posSec = (player.currentPosition.coerceAtLeast(0L)) / 1000.0
            currentTimeSec = posSec.coerceIn(0.0, totalDurationSec)
            delay(16L)
        }
    }

    // Persistent VideoFrameScrubber strictly active only in fallback mode + active stage
    var fallbackScrubber by remember(media.uri) { mutableStateOf<VideoFrameScrubber?>(null) }
    var fallbackBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(media.uri, isActiveStage, useFrameFallback) {
        if (!isActiveStage || !useFrameFallback) {
            fallbackScrubber = null
            onDispose {}
        } else {
            val scrubber = VideoFrameScrubber(context, media.uri, media.durationSec)
            fallbackScrubber = scrubber
            onDispose {
                fallbackScrubber = null
                scrubber.close()
            }
        }
    }

    // Pre-warm fallback scrubber timeline gently once when fallback opens
    LaunchedEffect(fallbackScrubber) {
        val s = fallbackScrubber ?: return@LaunchedEffect
        s.prewarmTimeline(maxSamples = 18)
    }

    // Progressive 120Hz frame decode for fallback mode (0ms RAM peek -> FAST_SCRUB -> EXACT_CRISP)
    LaunchedEffect(fallbackScrubber, currentTimeSec, isScrubbing) {
        val scrubber = fallbackScrubber ?: return@LaunchedEffect
        scrubber.peekInstantFrame(currentTimeSec, allowNearbyFallback = true)?.let { cached ->
            fallbackBitmap = cached.bitmap
        }
        val quality = if (isScrubbing) PreviewQuality.FAST_SCRUB else PreviewQuality.EXACT_CRISP
        val decoded = scrubber.extractFrame(currentTimeSec, quality)
        if (decoded != null) {
            fallbackBitmap = decoded
        }
        if (!isScrubbing && !scrubber.hasCrispFrame(currentTimeSec)) {
            delay(85L)
            scrubber.extractFrame(currentTimeSec, PreviewQuality.EXACT_CRISP)?.let { crisp ->
                fallbackBitmap = crisp
            }
        }
    }

    // Gentle frame-stepping playback in fallback mode if user taps Play on an unsupported container
    LaunchedEffect(useFrameFallback, isPlaying, isScrubbing) {
        if (!useFrameFallback || !isPlaying || isScrubbing) return@LaunchedEffect
        while (isActive && useFrameFallback && isPlaying && !isScrubbing) {
            delay(140L)
            val next = currentTimeSec + 0.14
            if (next >= totalDurationSec) {
                currentTimeSec = totalDurationSec
                isPlaying = false
                showPlayOverlay = true
            } else {
                currentTimeSec = next
            }
        }
    }

    fun seekVideoTo(targetSec: Double, dragging: Boolean) {
        val clamped = targetSec.coerceIn(0.0, totalDurationSec)
        currentTimeSec = clamped
        val player = exoPlayer
        if (player != null && !useFrameFallback) {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (!dragging || nowMs - lastExoSeekWallMs >= 65L) {
                lastExoSeekWallMs = nowMs
                runCatching { player.seekTo((clamped * 1000.0).toLong()) }
            }
        }
    }

    fun pausePlayback() {
        val player = exoPlayer
        if (player != null && player.isPlaying) {
            runCatching { player.pause() }
        }
        isPlaying = false
    }

    fun togglePlayPause() {
        if (!useFrameFallback) {
            val player = exoPlayer ?: return
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                showPlayOverlay = true
            } else {
                if (player.playbackState == Player.STATE_ENDED || currentTimeSec >= totalDurationSec - 0.15) {
                    player.seekTo(0L)
                    currentTimeSec = 0.0
                } else {
                    player.seekTo((currentTimeSec * 1000.0).toLong())
                }
                player.play()
                isPlaying = true
                showPlayOverlay = true
            }
        } else {
            if (isPlaying) {
                isPlaying = false
                showPlayOverlay = true
            } else {
                if (currentTimeSec >= totalDurationSec - 0.15) {
                    currentTimeSec = 0.0
                }
                isPlaying = true
                showPlayOverlay = true
            }
        }
    }

    // Load exact bundled UTF-8 Typeface for live Compose overlay (matches FFmpeg libass & Media3 overlay 1:1)
    val previewFontFamily = remember(config.subtitleFont) {
        val tf = runCatching {
            Typeface.createFromAsset(context.assets, config.subtitleFont.assetPath)
        }.getOrDefault(Typeface.DEFAULT_BOLD)
        FontFamily(tf)
    }

    val activeCueIndex = remember(config.subtitleCues, currentTimeSec) {
        SubtitleParser.findActiveCueIndex(config.subtitleCues, currentTimeSec)
    }
    val activeCue = config.subtitleCues.getOrNull(activeCueIndex)
    val selectedCueIndex = remember(config.subtitleCues, selectedCueId) {
        selectedCueId?.let { id -> config.subtitleCues.indexOfFirst { it.id == id } } ?: -1
    }
    val selectedCue = config.subtitleCues.getOrNull(selectedCueIndex)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Subtitle Source Bar (External .srt/.ass/.vtt picker + embedded tracks + manual Add Line)
        ConfigSectionCard(
            title = strings.subSourceCardTitle,
            subtitle = if (config.subtitleCues.isNotEmpty()) {
                strings.subLoadedSummary(
                    config.subtitleSourceName.ifBlank { "subtitles.srt" },
                    config.subtitleCues.size
                )
            } else {
                strings.subEmptyHint
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPickSubtitleFile,
                    modifier = Modifier
                        .weight(1.35f)
                        .height(44.dp)
                        .testTag("pick_subtitle_file_button"),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.subPickExternalButton,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = {
                        pausePlayback()
                        val newId = onAddCueAt(currentTimeSec)
                        selectedCueId = newId
                    },
                    modifier = Modifier
                        .weight(0.75f)
                        .height(44.dp)
                        .testTag("add_subtitle_cue_button"),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = strings.subAddManualCueButton,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val embeddedTextTracks = remember(media.subtitleTracks) {
                media.subtitleTracks.filter { it.isTextBased }
            }
            if (embeddedTextTracks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    embeddedTextTracks.take(6).forEach { track ->
                        val isLoaded = config.subtitleSourceEmbeddedIndex == track.subtitleStreamIndex
                        val trackLabel = buildString {
                            append(track.formatTag)
                            if (!track.language.isNullOrBlank()) append(" · ${track.language.uppercase()}")
                            else if (!track.title.isNullOrBlank()) append(" · ${track.title.take(12)}")
                            else append(" #${track.subtitleStreamIndex + 1}")
                        }
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onLoadEmbeddedTrack(track) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isLoaded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                width = if (isLoaded) 1.3.dp else 1.dp,
                                color = if (isLoaded) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Subtitles,
                                    contentDescription = null,
                                    tint = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = strings.subLoadEmbeddedButton(trackLabel),
                                    style = MonoLabelStyle.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isLoaded) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            if (isLoadingSubtitle) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = strings.subLoadingTrack,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. Large Horizontal 16:9 Live Video Stage + Scrub Bar + Time Boxes + Infinite Ribbon + Inline Editor
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, extColors.subtleBorder),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Large Horizontal 16:9 Video Frame (Media3 ExoPlayer with audio OR Seamless Frame Scrubber fallback)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF14100C))
                        .clickable {
                            if (isPlaying && !showPlayOverlay) {
                                showPlayOverlay = true
                            } else {
                                togglePlayPause()
                            }
                        }
                        .testTag("subtitle_video_stage"),
                    contentAlignment = Alignment.Center
                ) {
                    if (!useFrameFallback) {
                        val videoFitModifier = if (videoAspect > (16f / 9f)) {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(videoAspect, matchHeightConstraintsFirst = false)
                        } else {
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
                        }
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).also { tv ->
                                    textureViewRef = tv
                                    exoPlayer?.let { p -> runCatching { p.setVideoTextureView(tv) } }
                                }
                            },
                            update = { tv ->
                                if (textureViewRef !== tv) {
                                    textureViewRef = tv
                                    exoPlayer?.let { p -> runCatching { p.setVideoTextureView(tv) } }
                                }
                            },
                            modifier = videoFitModifier
                        )
                    } else {
                        val bmp = fallbackBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFF3ECE4)
                            )
                        }
                    }

                    // Top-left engine indicator pill (Media3 Live vs Silent Frame Fallback)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xAA1A1410)
                    ) {
                        Text(
                            text = if (useFrameFallback) strings.subPreviewFallbackBadge else strings.subPreviewLiveBadge,
                            style = MonoLabelStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                            color = Color(0xFFF6EFE8),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }

                    // Live WYSIWYG Subtitle Overlay (Exact chosen bundled UTF-8 font & size, bottom-center)
                    if (activeCue != null && activeCue.text.isNotBlank()) {
                        LiveSubtitleOverlayText(
                            text = activeCue.text,
                            fontFamily = previewFontFamily,
                            fontSizeSp = config.subtitleFontSize.previewBaseSp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }

                    // In-place Play/Pause center icon (never launches external video player!)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showPlayOverlay || !isPlaying,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(220)),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .clickable { togglePlayPause() }
                                .testTag("subtitle_play_pause_button"),
                            shape = CircleShape,
                            color = Color(0xB82E180B),
                            border = BorderStroke(1.5.dp, Color(0xCCF7EDE2))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = strings.subPlayPauseCd,
                                    tint = Color(0xFFFFF8F0),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }

                // Trim-Style 120Hz Dark-Brown Scrub Bar with Subtle Subtitle Cue Ticks
                SubtitleTimelineScrubBar(
                    currentTimeSec = currentTimeSec,
                    totalDurationSec = totalDurationSec,
                    cues = config.subtitleCues,
                    onScrubStart = {
                        pausePlayback()
                        isScrubbing = true
                    },
                    onScrubMove = { sec ->
                        seekVideoTo(sec, dragging = true)
                    },
                    onScrubEnd = { sec ->
                        isScrubbing = false
                        seekVideoTo(sec, dragging = false)
                    }
                )

                // Trim-Style Side-by-Side Time Boxes (Editable Current Time + Active Cue / Total Duration)
                SubtitleTimeBoxesRow(
                    currentTimeSec = currentTimeSec,
                    totalDurationSec = totalDurationSec,
                    activeCue = activeCue,
                    isExternalMotionActive = isScrubbing || isPlaying,
                    onJumpToTime = { target ->
                        pausePlayback()
                        seekVideoTo(target, dragging = false)
                    }
                )

                // Infinite Right-to-Left Virtualized Horizontal Subtitle Preview Ribbon
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.subRibbonTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.subRibbonHint,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    InfiniteHorizontalSubtitleRibbon(
                        cues = config.subtitleCues,
                        currentTimeSec = currentTimeSec,
                        totalDurationSec = totalDurationSec,
                        activeCueIndex = activeCueIndex,
                        selectedCueId = selectedCueId,
                        emptyHint = strings.subRibbonEmptyCueHint,
                        onDragStart = {
                            pausePlayback()
                            isScrubbing = true
                        },
                        onTimeUpdate = { sec, dragging ->
                            seekVideoTo(sec, dragging = dragging)
                        },
                        onDragSettled = { finalSec ->
                            isScrubbing = false
                            seekVideoTo(finalSec, dragging = false)
                        },
                        onCueTapped = { cue ->
                            pausePlayback()
                            selectedCueId = cue.id
                            val focusTime = (cue.startSec + minOf(0.25, (cue.endSec - cue.startSec) * 0.25))
                                .coerceIn(0.0, totalDurationSec)
                            seekVideoTo(focusTime, dragging = false)
                        }
                    )
                }

                // Direct Inline Subtitle Editor Box (Expands immediately beneath the tapped subtitle!)
                AnimatedVisibility(
                    visible = selectedCue != null,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                ) {
                    if (selectedCue != null) {
                        InlineSubtitleEditorCard(
                            cue = selectedCue,
                            cueDisplayNumber = selectedCueIndex + 1,
                            totalDurationSec = totalDurationSec,
                            onUpdateCue = onUpdateCue,
                            onDelete = {
                                onDeleteCue(selectedCue.id)
                                selectedCueId = null
                            },
                            onClose = { selectedCueId = null }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSubtitleOverlayText(
    text: String,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    modifier: Modifier = Modifier
) {
    val baseStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.24f).sp,
        textAlign = TextAlign.Center
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Crisp dark outline layer
        Text(
            text = text,
            style = baseStyle.copy(
                color = Color(0xFF120E0A),
                drawStyle = Stroke(
                    width = (fontSizeSp * 0.24f).coerceIn(3.5f, 7f),
                    join = StrokeJoin.Round
                )
            ),
            textAlign = TextAlign.Center
        )
        // Bright white fill with subtle drop shadow
        Text(
            text = text,
            style = baseStyle.copy(
                color = Color.White,
                shadow = Shadow(
                    color = Color(0xC8000000),
                    offset = Offset(0f, 2f),
                    blurRadius = 4f
                )
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SubtitleTimelineScrubBar(
    currentTimeSec: Double,
    totalDurationSec: Double,
    cues: List<SubtitleCue>,
    onScrubStart: () -> Unit,
    onScrubMove: (Double) -> Unit,
    onScrubEnd: (Double) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val trackBg = MaterialTheme.colorScheme.surfaceVariant
    val thumbFill = MaterialTheme.colorScheme.surface
    val latestDur by rememberUpdatedState(totalDurationSec)
    val latestOnStart by rememberUpdatedState(onScrubStart)
    val latestOnMove by rememberUpdatedState(onScrubMove)
    val latestOnEnd by rememberUpdatedState(onScrubEnd)

    var lastDraggedSec by remember { mutableDoubleStateOf(currentTimeSec) }

    val fraction = (currentTimeSec / totalDurationSec.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(totalDurationSec) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val f = (offset.x / w).coerceIn(0f, 1f)
                    val sec = f * latestDur
                    latestOnStart()
                    latestOnEnd(sec)
                }
            }
            .pointerInput(totalDurationSec) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val f = (offset.x / w).coerceIn(0f, 1f)
                        lastDraggedSec = f * latestDur
                        latestOnStart()
                        latestOnMove(lastDraggedSec)
                    },
                    onDragEnd = {
                        latestOnEnd(lastDraggedSec)
                    },
                    onDragCancel = {
                        latestOnEnd(lastDraggedSec)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val f = (change.position.x / w).coerceIn(0f, 1f)
                        lastDraggedSec = f * latestDur
                        latestOnMove(lastDraggedSec)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            val padX = 11.dp.toPx()
            val trackW = (size.width - padX * 2f).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val trackH = 6.dp.toPx()
            val radius = CornerRadius(trackH / 2f, trackH / 2f)

            // Base rail
            drawRoundRect(
                color = trackBg,
                topLeft = Offset(padX, centerY - trackH / 2f),
                size = Size(trackW, trackH),
                cornerRadius = radius
            )

            // Subtle cue tick markers along the rail (capped at 160 ticks for O(1) 120Hz paint)
            if (cues.isNotEmpty() && totalDurationSec > 0.1) {
                val stride = (cues.size / 160).coerceAtLeast(1)
                var i = 0
                while (i < cues.size) {
                    val c = cues[i]
                    val fStart = (c.startSec / totalDurationSec).toFloat().coerceIn(0f, 1f)
                    val fEnd = (c.endSec / totalDurationSec).toFloat().coerceIn(fStart, 1f)
                    val x0 = padX + fStart * trackW
                    val x1 = (padX + fEnd * trackW).coerceAtLeast(x0 + 2.dp.toPx())
                    drawRoundRect(
                        color = secondary.copy(alpha = 0.35f),
                        topLeft = Offset(x0, centerY - trackH / 2f),
                        size = Size((x1 - x0).coerceAtMost(padX + trackW - x0), trackH),
                        cornerRadius = radius
                    )
                    i += stride
                }
            }

            // Filled elapsed rail
            val thumbX = padX + fraction * trackW
            drawRoundRect(
                color = primary,
                topLeft = Offset(padX, centerY - trackH / 2f),
                size = Size((thumbX - padX).coerceAtLeast(0f), trackH),
                cornerRadius = radius
            )

            // Crisp Cello-brown thumb ring
            val thumbR = 9.5.dp.toPx()
            drawCircle(
                color = primary,
                radius = thumbR,
                center = Offset(thumbX, centerY)
            )
            drawCircle(
                color = thumbFill,
                radius = thumbR * 0.52f,
                center = Offset(thumbX, centerY)
            )
        }
    }
}

@Composable
private fun SubtitleTimeBoxesRow(
    currentTimeSec: Double,
    totalDurationSec: Double,
    activeCue: SubtitleCue?,
    isExternalMotionActive: Boolean,
    onJumpToTime: (Double) -> Unit
) {
    val strings = LocalAppStrings.current
    val extColors = LocalExtendedColors.current
    val formattedNow = FfmpegMath.formatKeyframeTime(currentTimeSec, strings)
    var editingText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isExternalMotionActive) {
        if (isExternalMotionActive && editingText != null) {
            editingText = null
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left box: Editable current timestamp
        OutlinedTextField(
            value = editingText ?: formattedNow,
            onValueChange = { raw ->
                editingText = raw
                val parsed = FfmpegMath.parseTimestampToSeconds(raw)
                if (parsed != null && parsed in 0.0..totalDurationSec) {
                    onJumpToTime(parsed)
                }
            },
            label = { Text(strings.subCurrentTimeLabel, style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            textStyle = MonoLabelStyle.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    editingText?.let { raw ->
                        FfmpegMath.parseTimestampToSeconds(raw)?.let { onJumpToTime(it) }
                    }
                    editingText = null
                }
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("subtitle_current_time_input")
        )

        // Right box: Active cue range OR total video duration
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, extColors.subtleBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (activeCue != null) {
                        "${strings.subInlineEditorStartLabel} → ${strings.subInlineEditorEndLabel}"
                    } else {
                        strings.subTotalDurationLabel
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (activeCue != null) {
                        "${FfmpegMath.formatKeyframeTime(activeCue.startSec, strings)} → ${FfmpegMath.formatKeyframeTime(activeCue.endSec, strings)}"
                    } else {
                        FfmpegMath.formatKeyframeTime(totalDurationSec, strings)
                    },
                    style = MonoLabelStyle.copy(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 120Hz Virtualized Infinite Right-to-Left Horizontal Subtitle Ribbon:
 * - Dragging right-to-left glides subtitles continuously to the left past a fixed center playhead.
 * - Full momentum fling decay via [Animatable.animateDecay].
 * - Only composes visible viewport cues via O(log N) [SubtitleParser.findVisibleCueRange].
 */
@Composable
private fun InfiniteHorizontalSubtitleRibbon(
    cues: List<SubtitleCue>,
    currentTimeSec: Double,
    totalDurationSec: Double,
    activeCueIndex: Int,
    selectedCueId: Long?,
    emptyHint: String,
    onDragStart: () -> Unit,
    onTimeUpdate: (Double, Boolean) -> Unit,
    onDragSettled: (Double) -> Unit,
    onCueTapped: (SubtitleCue) -> Unit
) {
    val extColors = LocalExtendedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val pxPerSec = with(density) { 96.dp.toPx() }
    val latestTime by rememberUpdatedState(currentTimeSec)
    val latestDur by rememberUpdatedState(totalDurationSec)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnTimeUpdate by rememberUpdatedState(onTimeUpdate)
    val latestOnDragSettled by rememberUpdatedState(onDragSettled)

    var flingJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { flingJob?.cancel() }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(98.dp)
            .testTag("infinite_subtitle_ribbon"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, extColors.subtleBorder)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(totalDurationSec, pxPerSec) {
                    val velocityTracker = VelocityTracker()
                    var dragTimeSec = 0.0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            flingJob?.cancel()
                            flingJob = null
                            velocityTracker.resetTracking()
                            dragTimeSec = latestTime
                            latestOnDragStart()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            // Right-to-left drag (dragAmount < 0) moves time forward so subtitles glide left endlessly
                            val deltaSec = -dragAmount / pxPerSec
                            dragTimeSec = (dragTimeSec + deltaSec).coerceIn(0.0, latestDur)
                            latestOnTimeUpdate(dragTimeSec, true)
                        },
                        onDragEnd = {
                            val vx = velocityTracker.calculateVelocity().x
                            val velSecPerSec = (-vx / pxPerSec).coerceIn(-60f, 60f)
                            if (abs(velSecPerSec) > 0.35f) {
                                flingJob?.cancel()
                                val startVal = dragTimeSec.toFloat()
                                val maxVal = latestDur.toFloat().coerceAtLeast(0.1f)
                                flingJob = scope.launch {
                                    val anim = Animatable(startVal)
                                    anim.updateBounds(lowerBound = 0f, upperBound = maxVal)
                                    try {
                                        anim.animateDecay(
                                            initialVelocity = velSecPerSec,
                                            animationSpec = exponentialDecay(frictionMultiplier = 1.65f)
                                        ) {
                                            latestOnTimeUpdate(value.toDouble(), true)
                                        }
                                    } finally {
                                        latestOnDragSettled(anim.value.toDouble())
                                    }
                                }
                            } else {
                                latestOnDragSettled(dragTimeSec)
                            }
                        },
                        onDragCancel = {
                            latestOnDragSettled(dragTimeSec)
                        }
                    )
                }
        ) {
            val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val halfW = viewportW / 2f
            val halfWindowSec = (halfW / pxPerSec) + 2.5
            val windowStart = (currentTimeSec - halfWindowSec).coerceAtLeast(0.0)
            val windowEnd = currentTimeSec + halfWindowSec

            // Top infinite scrolling time-tick ruler drawn directly on Canvas (0 recompositions/allocations)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.TopStart)
            ) {
                val firstSec = floor(windowStart).toInt().coerceAtLeast(0)
                val lastSec = ceil(windowEnd).toInt().coerceAtMost(ceil(totalDurationSec).toInt() + 2)
                for (sec in firstSec..lastSec) {
                    val x = halfW + ((sec - currentTimeSec) * pxPerSec).toFloat()
                    if (x < -4f || x > size.width + 4f) continue
                    val isMajor = sec % 5 == 0
                    val tickH = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
                    drawLine(
                        color = if (isMajor) secondary.copy(alpha = 0.55f) else secondary.copy(alpha = 0.25f),
                        start = Offset(x, 0f),
                        end = Offset(x, tickH),
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }
                // Baseline divider under ticks
                drawLine(
                    color = secondary.copy(alpha = 0.16f),
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1f
                )
            }

            // Virtualized visible subtitle cue pills positioned with graphicsLayer { translationX }
            val visibleRange = SubtitleParser.findVisibleCueRange(cues, windowStart, windowEnd)
            if (cues.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 20.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            } else {
                val minCuePx = with(density) { 86.dp.toPx() }
                val maxCuePx = with(density) { 380.dp.toPx() }
                for (idx in visibleRange) {
                    val cue = cues[idx]
                    val xPx = halfW + ((cue.startSec - currentTimeSec) * pxPerSec).toFloat()
                    val spanPx = ((cue.endSec - cue.startSec).coerceAtLeast(0.5) * pxPerSec)
                        .toFloat()
                        .coerceIn(minCuePx, maxCuePx)
                    val spanDp = with(density) { spanPx.toDp() }
                    val isSelected = cue.id == selectedCueId
                    val isActive = idx == activeCueIndex

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 10.dp)
                            .graphicsLayer { translationX = xPx }
                            .width(spanDp)
                            .height(60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCueTapped(cue) }
                            .testTag("subtitle_ribbon_cue_${cue.id}"),
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            isActive -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            width = if (isSelected) 1.8.dp else if (isActive) 1.3.dp else 1.dp,
                            color = when {
                                isSelected -> primary
                                isActive -> primary.copy(alpha = 0.55f)
                                else -> extColors.subtleBorder
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = FfmpegMath.formatKeyframeTime(cue.startSec),
                                style = MonoLabelStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                                color = if (isSelected || isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                text = cue.text.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 14.5.sp,
                                    fontWeight = if (isSelected || isActive) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Fixed Center Playhead Needle (stays locked at 50% X while subtitles glide past right-to-left)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                drawLine(
                    color = primary,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                val triW = 6.dp.toPx()
                val triH = 7.dp.toPx()
                val path = Path().apply {
                    moveTo(cx - triW, 0f)
                    lineTo(cx + triW, 0f)
                    lineTo(cx, triH)
                    close()
                }
                drawPath(path = path, color = primary)
            }
        }
    }
}

@Composable
private fun InlineSubtitleEditorCard(
    cue: SubtitleCue,
    cueDisplayNumber: Int,
    totalDurationSec: Double,
    onUpdateCue: (cueId: Long, newText: String, newStartSec: Double?, newEndSec: Double?) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalAppStrings.current
    var startText by remember(cue.id) { mutableStateOf(FfmpegMath.formatKeyframeTime(cue.startSec, strings)) }
    var endText by remember(cue.id) { mutableStateOf(FfmpegMath.formatKeyframeTime(cue.endSec, strings)) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subtitle_inline_editor"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.36f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = strings.subInlineEditorTitle(cueDisplayNumber),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = strings.subInlineEditorDelete,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    TextButton(
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = strings.subInlineEditorDone,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            OutlinedTextField(
                value = cue.text,
                onValueChange = { next ->
                    onUpdateCue(cue.id, next, null, null)
                },
                label = { Text(strings.subInlineEditorTextLabel) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subtitle_inline_text_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { raw ->
                        startText = raw
                        val parsed = FfmpegMath.parseTimestampToSeconds(raw)
                        if (parsed != null && parsed >= 0.0 && parsed < totalDurationSec) {
                            onUpdateCue(cue.id, cue.text, parsed, null)
                        }
                    },
                    label = { Text(strings.subInlineEditorStartLabel, style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MonoLabelStyle.copy(fontSize = 12.5.sp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { raw ->
                        endText = raw
                        val parsed = FfmpegMath.parseTimestampToSeconds(raw)
                        if (parsed != null && parsed > cue.startSec + 0.09) {
                            onUpdateCue(cue.id, cue.text, null, parsed)
                        }
                    },
                    label = { Text(strings.subInlineEditorEndLabel, style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MonoLabelStyle.copy(fontSize = 12.5.sp),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun SubtitleEmbedModeCard(
    selectedMode: SubtitleEmbedMode,
    softsubContainer: SubtitleSoftsubContainer,
    capabilities: EncoderCapabilities,
    onSelectEmbedMode: (SubtitleEmbedMode) -> Unit,
    onSelectSoftsubContainer: (SubtitleSoftsubContainer) -> Unit
) {
    val strings = LocalAppStrings.current
    val extColors = LocalExtendedColors.current

    ConfigSectionCard(
        title = strings.subEmbedModeTitle
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(SubtitleEmbedMode.SOFTSUB, strings.subSoftsubTitle, strings.subSoftsubSub),
                    Triple(SubtitleEmbedMode.HARDSUB, strings.subHardsubTitle, strings.subHardsubSub)
                ).forEach { (mode, title, sub) ->
                    val selected = selectedMode == mode
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelectEmbedMode(mode) }
                            .testTag("subtitle_embed_mode_${mode.name.lowercase()}"),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            width = if (selected) 1.6.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (selectedMode == SubtitleEmbedMode.SOFTSUB) {
                Text(
                    text = strings.subSoftsubContainerLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubtitleSoftsubContainer.entries.forEach { container ->
                        val selected = softsubContainer == container
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectSoftsubContainer(container) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                            )
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = container.label,
                                    style = MonoLabelStyle.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            if (selectedMode == SubtitleEmbedMode.HARDSUB && capabilities.probed && !capabilities.libassSupportsHardsub) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = strings.subHardsubUnavailableNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun SubtitleFontAndSizeSection(
    selectedFont: SubtitleFontOption,
    selectedSize: SubtitleFontSizeOption,
    onSelectFont: (SubtitleFontOption) -> Unit,
    onSelectSize: (SubtitleFontSizeOption) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val extColors = LocalExtendedColors.current

    val loadedFontFamilies = remember {
        SubtitleFontOption.entries.associateWith { opt ->
            val tf = runCatching {
                Typeface.createFromAsset(context.assets, opt.assetPath)
            }.getOrDefault(Typeface.DEFAULT_BOLD)
            FontFamily(tf)
        }
    }

    ConfigSectionCard(
        title = strings.subFontSectionTitle,
        subtitle = strings.subFontSectionSub
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubtitleFontOption.entries.forEach { fontOpt ->
                    val selected = selectedFont == fontOpt
                    val family = loadedFontFamilies[fontOpt] ?: FontFamily.Default
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelectFont(fontOpt) }
                            .testTag("subtitle_font_${fontOpt.name.lowercase()}"),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            width = if (selected) 1.6.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = fontOpt.displayName,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = family,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Ğğ Şş İı",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = family,
                                    fontSize = 11.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Text(
                                text = fontOpt.localizedTag(strings),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Text(
                text = strings.subFontSizeLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SubtitleFontSizeOption.entries.forEach { sizeOpt ->
                    val selected = selectedSize == sizeOpt
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectSize(sizeOpt) }
                            .testTag("subtitle_size_${sizeOpt.name.lowercase()}"),
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else extColors.subtleBorder
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sizeOpt.localizedLabel(strings) + if (sizeOpt.isRecommended) " ★" else "",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
