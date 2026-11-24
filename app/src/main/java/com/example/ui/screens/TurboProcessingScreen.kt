package com.example.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.CompletedOutput
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.LocalAppStrings
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoLabelStyle
import com.example.ui.theme.PlayfairDisplayFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class TurboPhase { RUNNING, SUCCESS, FAILED, IDLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurboProcessingScreen(
    state: EncodingState,
    isActiveStage: Boolean = true,
    onCancel: () -> Unit,
    onOpenOutput: (CompletedOutput) -> Unit,
    onShareOutput: (CompletedOutput) -> Unit,
    onDoneReturnHome: () -> Unit,
    onTryRemuxAndRetry: () -> Unit = {},
    onFallbackToFfmpeg: () -> Unit,
    onFallbackToFfmpegFast: () -> Unit = onFallbackToFfmpeg
) {
    BackHandler(enabled = isActiveStage) {
        if (state !is EncodingState.Running) {
            onDoneReturnHome()
        }
    }

    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val canOfferRemux = state is EncodingState.Failed &&
        state.isHardwareTurbo &&
        state.isUnsupportedFormat &&
        !state.hasTriedRemux
    var showRemuxPromptDialog by remember(state) { mutableStateOf(canOfferRemux) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = strings.turboScreenTitle,
                            style = MaterialTheme.typography.titleLarge
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
                border = BorderStroke(1.dp, extColors.subtleBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (state) {
                        is EncodingState.Running -> {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("turbo_cancel_button"),
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(strings.actionCancel)
                            }
                        }

                        is EncodingState.Success -> {
                            Button(
                                onClick = onDoneReturnHome,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("turbo_new_task_button"),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(strings.actionNewTask)
                            }
                        }

                        is EncodingState.Failed -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (canOfferRemux) {
                                    FilledTonalButton(
                                        onClick = { showRemuxPromptDialog = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("turbo_try_remux_button"),
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
                                    ) {
                                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = strings.turboTryRemuxButton,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onDoneReturnHome,
                                        modifier = Modifier
                                            .weight(0.8f)
                                            .height(50.dp)
                                            .testTag("turbo_failed_home_button"),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(strings.actionHome)
                                    }
                                    Button(
                                        onClick = {
                                            if (state.hasTriedRemux) onFallbackToFfmpegFast()
                                            else onFallbackToFfmpeg()
                                        },
                                        modifier = Modifier
                                            .weight(1.4f)
                                            .height(50.dp)
                                            .testTag("turbo_fallback_ffmpeg_button"),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (state.hasTriedRemux) {
                                                strings.turboFallbackFastFfmpegButton
                                            } else {
                                                strings.turboFallbackToFfmpegButton
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        EncodingState.Idle -> Unit
                    }
                }
            }
        }
    ) { innerPadding ->
        var lastRunning by remember { mutableStateOf<EncodingState.Running?>(null) }
        var lastSuccess by remember { mutableStateOf<EncodingState.Success?>(null) }
        var lastFailed by remember { mutableStateOf<EncodingState.Failed?>(null) }
        when (state) {
            is EncodingState.Running -> lastRunning = state
            is EncodingState.Success -> lastSuccess = state
            is EncodingState.Failed -> lastFailed = state
            EncodingState.Idle -> Unit
        }
        val phase = when (state) {
            is EncodingState.Running -> TurboPhase.RUNNING
            is EncodingState.Success -> TurboPhase.SUCCESS
            is EncodingState.Failed -> TurboPhase.FAILED
            EncodingState.Idle -> TurboPhase.IDLE
        }
        AnimatedContent(
            targetState = phase,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(260)) { it / 18 }) togetherWith
                    fadeOut(tween(160))
            },
            label = "turbo_phase_transition"
        ) { targetPhase ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (targetPhase) {
                    TurboPhase.RUNNING -> {
                        val running = (state as? EncodingState.Running) ?: lastRunning
                        if (running != null) {
                            item {
                                TurboSpeedometerHeroCard(state = running)
                            }
                            item {
                                TurboPreservedSpecsCard(state = running)
                            }
                        }
                    }

                    TurboPhase.SUCCESS -> {
                        val success = (state as? EncodingState.Success) ?: lastSuccess
                        if (success != null) {
                            item {
                                TurboSuccessBanner(
                                    summary = success.summaryMessage,
                                    elapsedSec = success.elapsedSec
                                )
                            }
                            items(success.outputs, key = { it.id }) { output ->
                                TurboCompletedOutputCard(
                                    output = output,
                                    onOpen = { onOpenOutput(output) },
                                    onShare = { onShareOutput(output) }
                                )
                            }
                        }
                    }

                    TurboPhase.FAILED -> {
                        val failed = (state as? EncodingState.Failed) ?: lastFailed
                        if (failed != null) {
                            item {
                                TurboFailureWarningCard(
                                    isUnsupportedFormat = failed.isUnsupportedFormat,
                                    hasTriedRemux = failed.hasTriedRemux,
                                    errorMessage = failed.errorMessage,
                                    hint = failed.hint
                                )
                            }
                        }
                    }

                    TurboPhase.IDLE -> Unit
                }
            }
        }
    }

    if (showRemuxPromptDialog && canOfferRemux && isActiveStage) {
        AlertDialog(
            onDismissRequest = { showRemuxPromptDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = strings.turboRemuxPromptTitle,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = strings.turboRemuxPromptBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemuxPromptDialog = false
                        onTryRemuxAndRetry()
                    },
                    modifier = Modifier.testTag("turbo_remux_confirm_yes")
                ) {
                    Text(strings.turboRemuxPromptYes)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRemuxPromptDialog = false },
                    modifier = Modifier.testTag("turbo_remux_confirm_no")
                ) {
                    Text(strings.turboRemuxPromptNo)
                }
            }
        )
    }
}

@Composable
private fun TurboSpeedometerHeroCard(state: EncodingState.Running) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val pct = (state.percent ?: 0).coerceIn(0, 100)
    val telemetry = FfmpegMath.formatTelemetryLine(
        currentSec = state.currentTimeSec,
        totalDurationSec = state.totalDurationSec,
        percent = state.percent,
        speed = state.speed,
        sizeKb = state.sizeKb,
        includePercent = false,
        strings = strings
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top hardware pill
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = strings.turboHardwareChip,
                        style = MonoLabelStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Automotive Speedometer Gauge (0 -> 100%)
            CarSpeedometerGauge(
                percent = pct,
                subLabel = strings.turboGaugeSubLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("turbo_speedometer_gauge")
            )

            if (state.statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(12.dp))

            // Classic live telemetry bar: Time processed / total | speed (e.g. 4.20x) | size
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, extColors.subtleBorder)
            ) {
                Text(
                    text = telemetry,
                    style = MonoCodeStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("turbo_telemetry_bar")
                )
            }
        }
    }
}

/**
 * Custom Canvas automotive speedometer / tachometer gauge (0..100 scale representing encoding %).
 * Styled in our Cream & Cello Dark-Brown palette with warm bronze active sweep and Sienna redline.
 */
@Composable
private fun CarSpeedometerGauge(
    percent: Int,
    subLabel: String,
    modifier: Modifier = Modifier
) {
    val clampedTarget = percent.coerceIn(0, 100).toFloat()
    val animatedValue by animateFloatAsState(
        targetValue = clampedTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 155f
        ),
        label = "speedometer_needle_sweep"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val redline = MaterialTheme.colorScheme.error
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dialFace = MaterialTheme.colorScheme.surface
    val bezelOuter = MaterialTheme.colorScheme.surfaceContainerHighest
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val outlineColor = MaterialTheme.colorScheme.outline
    val strings = LocalAppStrings.current
    val density = LocalDensity.current

    val numberPaint = remember(density, ink) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = ink.toArgb()
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val gaugeSize = min(maxWidth.value, 286f).dp
        Box(
            modifier = Modifier
                .size(gaugeSize)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val diameter = min(size.width, size.height)
                val radius = diameter / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                val startAngleDeg = 145f
                val totalSweepDeg = 250f
                val redlineStartFraction = 0.80f

                // 1. Machined outer bezel + recessed ivory dial face
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(bezelOuter, outlineColor.copy(alpha = 0.55f)),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = primary.copy(alpha = 0.28f),
                    radius = radius - 1.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(dialFace, bezelOuter.copy(alpha = 0.45f)),
                        center = Offset(center.x, center.y - radius * 0.12f),
                        radius = radius * 0.94f
                    ),
                    radius = radius * 0.93f,
                    center = center
                )
                drawCircle(
                    color = outlineColor.copy(alpha = 0.45f),
                    radius = radius * 0.93f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // 2. Background track arc & redline zone arc
                val arcInset = radius * 0.145f
                val arcSize = Size(diameter - arcInset * 2f, diameter - arcInset * 2f)
                val arcTopLeft = Offset(center.x - arcSize.width / 2f, center.y - arcSize.height / 2f)
                val trackStrokeWidth = radius * 0.095f

                drawArc(
                    color = trackBg,
                    startAngle = startAngleDeg,
                    sweepAngle = totalSweepDeg,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = trackStrokeWidth, cap = StrokeCap.Round)
                )

                // Subtle redline zone track (80..100)
                drawArc(
                    color = redline.copy(alpha = 0.22f),
                    startAngle = startAngleDeg + totalSweepDeg * redlineStartFraction,
                    sweepAngle = totalSweepDeg * (1f - redlineStartFraction),
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = trackStrokeWidth, cap = StrokeCap.Round)
                )

                // 3. Active sweeping progress arc with soft glow
                val progressFraction = (animatedValue / 100f).coerceIn(0f, 1f)
                val activeSweepDeg = totalSweepDeg * progressFraction
                if (activeSweepDeg > 0.5f) {
                    val activeBrush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.0f to secondary,
                            0.45f to primary,
                            0.80f to primary,
                            1.0f to redline
                        ),
                        center = center
                    )
                    // Glow underlay
                    drawArc(
                        color = if (progressFraction >= redlineStartFraction) {
                            redline.copy(alpha = 0.22f)
                        } else {
                            secondary.copy(alpha = 0.20f)
                        },
                        startAngle = startAngleDeg,
                        sweepAngle = activeSweepDeg,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = trackStrokeWidth * 1.55f, cap = StrokeCap.Round)
                    )
                    // Crisp foreground arc
                    drawArc(
                        brush = activeBrush,
                        startAngle = startAngleDeg,
                        sweepAngle = activeSweepDeg,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = trackStrokeWidth, cap = StrokeCap.Round)
                    )
                }

                // 4. Automotive tick marks (0..100: major every 10, medium every 5, minor every 2)
                val outerTickRadius = radius * 0.805f
                val labelRadius = radius * 0.625f
                numberPaint.textSize = radius * 0.105f

                for (step in 0..50) {
                    val value = step * 2
                    val frac = value / 100f
                    val angleDeg = startAngleDeg + totalSweepDeg * frac
                    val angleRad = angleDeg * (PI / 180.0)
                    val isMajor = value % 10 == 0
                    val isMedium = !isMajor && value % 5 == 0
                    val isRedline = value >= 80
                    val isLit = value <= animatedValue + 0.5f

                    val tickLen = when {
                        isMajor -> radius * 0.088f
                        isMedium -> radius * 0.058f
                        else -> radius * 0.036f
                    }
                    val tickStroke = when {
                        isMajor -> 2.3.dp.toPx()
                        isMedium -> 1.5.dp.toPx()
                        else -> 1.0.dp.toPx()
                    }
                    val tickColor = when {
                        isRedline && isLit -> redline
                        isRedline -> redline.copy(alpha = 0.55f)
                        isLit -> primary
                        isMajor -> ink.copy(alpha = 0.62f)
                        else -> muted.copy(alpha = 0.36f)
                    }

                    val innerR = outerTickRadius - tickLen
                    val startPt = Offset(
                        x = center.x + (innerR * cos(angleRad)).toFloat(),
                        y = center.y + (innerR * sin(angleRad)).toFloat()
                    )
                    val endPt = Offset(
                        x = center.x + (outerTickRadius * cos(angleRad)).toFloat(),
                        y = center.y + (outerTickRadius * sin(angleRad)).toFloat()
                    )
                    drawLine(
                        color = tickColor,
                        start = startPt,
                        end = endPt,
                        strokeWidth = tickStroke,
                        cap = StrokeCap.Round
                    )

                    if (isMajor) {
                        val lx = center.x + (labelRadius * cos(angleRad)).toFloat()
                        val ly = center.y + (labelRadius * sin(angleRad)).toFloat()
                        numberPaint.color = when {
                            isRedline -> redline.toArgb()
                            isLit -> primary.toArgb()
                            else -> muted.toArgb()
                        }
                        val fm = numberPaint.fontMetrics
                        val textY = ly - (fm.ascent + fm.descent) / 2f
                        drawContext.canvas.nativeCanvas.drawText(
                            value.toString(),
                            lx,
                            textY,
                            numberPaint
                        )
                    }
                }

                // 5. Inner delicate compass ring around center pivot
                drawCircle(
                    color = outlineColor.copy(alpha = 0.35f),
                    radius = radius * 0.24f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // 6. Tapered Automotive Needle + Counterweight
                val needleAngleDeg = startAngleDeg + totalSweepDeg * progressFraction
                val needleRad = needleAngleDeg * (PI / 180.0)
                val perpRad = needleRad + (PI / 2.0)

                val tipLength = radius * 0.72f
                val tailLength = radius * 0.16f
                val baseHalfWidth = radius * 0.036f
                val tipHalfWidth = radius * 0.008f

                val cosA = cos(needleRad).toFloat()
                val sinA = sin(needleRad).toFloat()
                val cosP = cos(perpRad).toFloat()
                val sinP = sin(perpRad).toFloat()

                fun needlePath(offset: Offset = Offset.Zero): Path {
                    val tipCenter = Offset(center.x + tipLength * cosA, center.y + tipLength * sinA) + offset
                    val tailCenter = Offset(center.x - tailLength * cosA, center.y - tailLength * sinA) + offset
                    return Path().apply {
                        moveTo(tipCenter.x + tipHalfWidth * cosP, tipCenter.y + tipHalfWidth * sinP)
                        lineTo(center.x + offset.x + baseHalfWidth * cosP, center.y + offset.y + baseHalfWidth * sinP)
                        lineTo(tailCenter.x + baseHalfWidth * 0.65f * cosP, tailCenter.y + baseHalfWidth * 0.65f * sinP)
                        lineTo(tailCenter.x - baseHalfWidth * 0.65f * cosP, tailCenter.y - baseHalfWidth * 0.65f * sinP)
                        lineTo(center.x + offset.x - baseHalfWidth * cosP, center.y + offset.y - baseHalfWidth * sinP)
                        lineTo(tipCenter.x - tipHalfWidth * cosP, tipCenter.y - tipHalfWidth * sinP)
                        close()
                    }
                }

                // Soft needle drop shadow
                drawPath(
                    path = needlePath(Offset(0f, 3.dp.toPx())),
                    color = Color.Black.copy(alpha = 0.14f)
                )
                // Main crimson-espresso racing needle
                drawPath(
                    path = needlePath(),
                    brush = Brush.linearGradient(
                        colors = listOf(primary, redline),
                        start = center,
                        end = Offset(center.x + tipLength * cosA, center.y + tipLength * sinA)
                    )
                )

                // 7. Layered machined bronze center hub cap
                drawCircle(
                    color = primary,
                    radius = radius * 0.095f,
                    center = center
                )
                drawCircle(
                    color = dialFace,
                    radius = radius * 0.056f,
                    center = center
                )
                drawCircle(
                    color = secondary,
                    radius = radius * 0.032f,
                    center = center
                )
            }

            // Digital Odometer Readout tucked cleanly in the open lower dial quadrant
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = strings.formatPercent(animatedValue.roundToInt().coerceIn(0, 100)),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = if (animatedValue >= 80f) redline else primary,
                    modifier = Modifier.testTag("turbo_percent_readout")
                )
                Text(
                    text = subLabel,
                    style = MonoLabelStyle.copy(
                        fontSize = 8.2.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.55.sp
                    ),
                    color = secondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun TurboPreservedSpecsCard(state: EncodingState.Running) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extColors.subtleBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = state.modeTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = strings.turboSettingsPreservedNote,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = PlayfairDisplayFontFamily,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            val stripText = state.videoSpecsSummary?.takeIf { it.isNotBlank() } ?: state.summaryBanner
            if (stripText.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, extColors.subtleBorder)
                ) {
                    Text(
                        text = stripText,
                        style = MonoLabelStyle.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("turbo_unified_specs_strip")
                    )
                }
            }

            Text(
                text = strings.wakeLockActiveSuffix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TurboSuccessBanner(
    summary: String,
    elapsedSec: Double
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val elapsedText = remember(elapsedSec, strings) {
        FfmpegMath.formatElapsedTime(elapsedSec, strings)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = extColors.losslessContainer,
        border = BorderStroke(1.dp, extColors.lossless.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(extColors.lossless.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = strings.successCd,
                    tint = extColors.lossless,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = strings.turboSuccessBadge,
                    style = MonoLabelStyle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                    color = extColors.lossless
                )
                Text(
                    text = strings.successTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = extColors.onLosslessContainer
                )
                Text(
                    text = "$summary · ${strings.turboElapsedLabel(elapsedText)}",
                    style = MonoLabelStyle,
                    color = extColors.onLosslessContainer.copy(alpha = 0.85f)
                )
                Text(
                    text = strings.successMediaStoreNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = extColors.onLosslessContainer.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun TurboCompletedOutputCard(
    output: CompletedOutput,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    val reductionText = remember(output, strings) {
        if (output.inputSizeBytes > 0 && output.outputSizeBytes > 0) {
            val deltaPct = ((1.0 - (output.outputSizeBytes.toDouble() / output.inputSizeBytes.toDouble())) * 100.0).toInt()
            if (deltaPct > 0) strings.smallerPercentBadge(deltaPct) else null
        } else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extColors.subtleBorder),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = output.fileName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = output.displayPath,
                    style = MonoLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Text(
                        text = FfmpegMath.formatSizeBytes(output.outputSizeBytes),
                        style = MonoLabelStyle.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (reductionText != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = extColors.losslessContainer
                    ) {
                        Text(
                            text = reductionText,
                            style = MonoLabelStyle,
                            color = extColors.onLosslessContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("turbo_open_output_button"),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.actionOpen)
                }
                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("turbo_share_output_button"),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.actionShare)
                }
            }
        }
    }
}

@Composable
private fun TurboFailureWarningCard(
    isUnsupportedFormat: Boolean,
    hasTriedRemux: Boolean,
    errorMessage: String,
    hint: String?
) {
    val strings = LocalAppStrings.current
    val titleText = when {
        hasTriedRemux -> strings.turboAfterRemuxFailedTitle
        isUnsupportedFormat -> strings.turboUnsupportedTitle
        else -> strings.failedErrorTitle
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("turbo_unsupported_warning_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
