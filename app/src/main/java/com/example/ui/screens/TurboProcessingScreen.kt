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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.CompletedOutput
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.LocalAppStrings
import com.example.domain.Media3CompatibilityAdvisor
import com.example.domain.TurboRecoveryStep
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

private class TurboStateCache {
    var lastRunning: EncodingState.Running? = null
    var lastSuccess: EncodingState.Success? = null
    var lastFailed: EncodingState.Failed? = null
}

private data class GaugeTickSpec(
    val value: Int,
    val cosA: Float,
    val sinA: Float,
    val isMajor: Boolean,
    val isMedium: Boolean,
    val isRedline: Boolean,
    val label: String?
)

private val SPEEDOMETER_TICKS: List<GaugeTickSpec> = List(51) { step ->
    val value = step * 2
    val angleDeg = 145f + 250f * (value / 100f)
    val angleRad = angleDeg * (PI / 180.0)
    val isMajor = value % 10 == 0
    GaugeTickSpec(
        value = value,
        cosA = cos(angleRad).toFloat(),
        sinA = sin(angleRad).toFloat(),
        isMajor = isMajor,
        isMedium = !isMajor && value % 5 == 0,
        isRedline = value >= 80,
        label = if (isMajor) value.toString() else null
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TurboProcessingScreen(
    state: EncodingState,
    isActiveStage: Boolean = true,
    onCancel: () -> Unit,
    onOpenOutput: (CompletedOutput) -> Unit,
    onEditOutput: (CompletedOutput) -> Unit = {},
    onShareOutput: (CompletedOutput) -> Unit,
    onDoneReturnHome: () -> Unit,
    onTryRemuxAndRetry: () -> Unit = {},
    onTryAudioConvertAndRetry: (Int) -> Unit = {},
    onOpenVideoOnlyPrep: () -> Unit = {},
    onOpenVideoAudioPrep: () -> Unit = {},
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
    val failedState = state as? EncodingState.Failed
    val effectiveRecoveryStep = if (failedState != null && failedState.isHardwareTurbo) {
        when {
            failedState.recoveryStep != TurboRecoveryStep.NONE -> failedState.recoveryStep
            failedState.isUnsupportedFormat && !failedState.hasTriedRemux -> TurboRecoveryStep.OFFER_REMUX
            else -> TurboRecoveryStep.NONE
        }
    } else {
        TurboRecoveryStep.NONE
    }
    val canOfferRemux = effectiveRecoveryStep == TurboRecoveryStep.OFFER_REMUX
    val canOfferAudioConvert = effectiveRecoveryStep == TurboRecoveryStep.OFFER_AUDIO_CONVERT
    val canOfferVideoOnly = effectiveRecoveryStep == TurboRecoveryStep.OFFER_VIDEO_ONLY
    val canOfferVideoAndAudio = effectiveRecoveryStep == TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO

    var showRemuxPromptDialog by remember(state) { mutableStateOf(canOfferRemux) }
    var showAudioConvertDialog by remember(state) { mutableStateOf(canOfferAudioConvert) }
    var showVideoOnlyDialog by remember(state) { mutableStateOf(canOfferVideoOnly) }
    var showVideoAudioDialog by remember(state) { mutableStateOf(canOfferVideoAndAudio) }
    var recoveryAudioInput by remember(state) {
        mutableStateOf((failedState?.recommendedAudioBitrateKbps ?: 256).toString())
    }
    val audioRecoveryChoices = remember(failedState) {
        Media3CompatibilityAdvisor.audioRecoveryBitrateChoices(
            sourceKbps = failedState?.sourceAudioBitrateKbps,
            isLosslessSource = failedState?.isLosslessSourceAudio == true,
            recommendedKbps = failedState?.recommendedAudioBitrateKbps ?: 256,
            maxKbps = 768
        )
    }
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state !is EncodingState.Running) {
        if (state !is EncodingState.Running) {
            showCancelConfirmDialog = false
        }
    }

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
                                onClick = { showCancelConfirmDialog = true },
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
                            val preferFastFfmpeg = state.hasTriedRemux ||
                                state.hasTriedAudioConvert ||
                                state.hasTriedVideoOnlyConvert ||
                                state.hasTriedVideoAudioConvert
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when {
                                    canOfferRemux -> {
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

                                    canOfferAudioConvert -> {
                                        FilledTonalButton(
                                            onClick = { showAudioConvertDialog = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("turbo_try_audio_convert_button"),
                                            shape = MaterialTheme.shapes.small,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
                                        ) {
                                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = strings.turboTryAudioConvertButton,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    canOfferVideoOnly -> {
                                        FilledTonalButton(
                                            onClick = { showVideoOnlyDialog = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("turbo_try_video_only_button"),
                                            shape = MaterialTheme.shapes.small,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
                                        ) {
                                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = strings.turboTryVideoOnlyButton,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    canOfferVideoAndAudio -> {
                                        FilledTonalButton(
                                            onClick = { showVideoAudioDialog = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("turbo_try_video_audio_button"),
                                            shape = MaterialTheme.shapes.small,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
                                        ) {
                                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = strings.turboTryVideoAudioButton,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
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
                                            if (preferFastFfmpeg) onFallbackToFfmpegFast()
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
                                            text = if (preferFastFfmpeg) {
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
        val stateCache = remember { TurboStateCache() }
        when (state) {
            is EncodingState.Running -> stateCache.lastRunning = state
            is EncodingState.Success -> stateCache.lastSuccess = state
            is EncodingState.Failed -> stateCache.lastFailed = state
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
                        val running = (state as? EncodingState.Running) ?: stateCache.lastRunning
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
                        val success = (state as? EncodingState.Success) ?: stateCache.lastSuccess
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
                                    onEdit = { onEditOutput(output) },
                                    onShare = { onShareOutput(output) }
                                )
                            }
                        }
                    }

                    TurboPhase.FAILED -> {
                        val failed = (state as? EncodingState.Failed) ?: stateCache.lastFailed
                        if (failed != null) {
                            item {
                                TurboFailureWarningCard(failed = failed)
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val diag = failedState?.diagnosisSummary
                    if (!diag.isNullOrBlank()) {
                        TurboDiagnosisBannerChip(text = diag)
                    }
                    Text(
                        text = strings.turboRemuxPromptBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

    if (showAudioConvertDialog && canOfferAudioConvert && failedState != null && isActiveStage) {
        val normalizedRecoveryKbps = FfmpegMath.normalizeAudioBitrate(
            raw = recoveryAudioInput,
            defaultKbps = failedState.recommendedAudioBitrateKbps,
            maxKbps = 768
        )
        val typedRecoveryKbps = FfmpegMath.parseAudioBitrateInput(recoveryAudioInput)
        val showRecoveryUpscaleWarning = typedRecoveryKbps != null && FfmpegMath.isAudioBitrateAboveSource(
            requestedKbps = typedRecoveryKbps,
            sourceKbps = failedState.sourceAudioBitrateKbps,
            isLosslessSource = failedState.isLosslessSourceAudio
        )
        AlertDialog(
            onDismissRequest = { showAudioConvertDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = strings.turboAudioConvertPromptTitle,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val diag = failedState.diagnosisSummary
                    if (!diag.isNullOrBlank()) {
                        TurboDiagnosisBannerChip(text = diag)
                    }
                    Text(
                        text = strings.turboAudioConvertPromptBody(failedState.hasTriedRemux),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = strings.turboAudioConvertBitrateLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = strings.turboAudioConvertSourceHint(
                            failedState.sourceAudioBitrateKbps,
                            failedState.isLosslessSourceAudio,
                            failedState.recommendedAudioBitrateKbps
                        ),
                        style = MonoLabelStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        audioRecoveryChoices.forEach { kbps ->
                            val kbpsStr = kbps.toString()
                            val isSelected = recoveryAudioInput.trim().replace("k", "", ignoreCase = true) == kbpsStr
                            FilterChip(
                                selected = isSelected,
                                onClick = { recoveryAudioInput = kbpsStr },
                                label = {
                                    Text(
                                        text = if (kbps == failedState.recommendedAudioBitrateKbps) "${kbpsStr}k ★" else "${kbpsStr}k",
                                        style = MonoLabelStyle.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                },
                                modifier = Modifier.testTag("turbo_audio_kbps_$kbpsStr"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = recoveryAudioInput,
                        onValueChange = { recoveryAudioInput = it },
                        label = { Text(strings.customBitrateLabel) },
                        suffix = { Text("k", style = MonoLabelStyle) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("turbo_audio_bitrate_input")
                    )
                    if (showRecoveryUpscaleWarning && failedState.sourceAudioBitrateKbps != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("turbo_audio_bitrate_upscale_warning"),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.68f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.38f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(16.dp)
                                )
                                Text(
                                    text = strings.audioBitrateUpscaleWarning(
                                        failedState.sourceAudioBitrateKbps,
                                        failedState.sourceAudioCodec?.uppercase() ?: "?",
                                        normalizedRecoveryKbps
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAudioConvertDialog = false
                        onTryAudioConvertAndRetry(normalizedRecoveryKbps)
                    },
                    modifier = Modifier.testTag("turbo_audio_convert_confirm_yes")
                ) {
                    Text(strings.turboAudioConvertPromptYes)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAudioConvertDialog = false },
                    modifier = Modifier.testTag("turbo_audio_convert_confirm_no")
                ) {
                    Text(strings.turboRemuxPromptNo)
                }
            }
        )
    }

    if (showVideoOnlyDialog && canOfferVideoOnly && failedState != null && isActiveStage) {
        AlertDialog(
            onDismissRequest = { showVideoOnlyDialog = false },
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
                    text = strings.turboVideoOnlyPromptTitle,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val diag = failedState.diagnosisSummary
                    if (!diag.isNullOrBlank()) {
                        TurboDiagnosisBannerChip(text = diag)
                    }
                    Text(
                        text = strings.turboVideoOnlyPromptBody(failedState.hasSourceAudio),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVideoOnlyDialog = false
                        onOpenVideoOnlyPrep()
                    },
                    modifier = Modifier.testTag("turbo_video_only_confirm_yes")
                ) {
                    Text(strings.turboVideoOnlyPromptYes)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showVideoOnlyDialog = false },
                    modifier = Modifier.testTag("turbo_video_only_confirm_no")
                ) {
                    Text(strings.turboRemuxPromptNo)
                }
            }
        )
    }

    if (showVideoAudioDialog && canOfferVideoAndAudio && failedState != null && isActiveStage) {
        AlertDialog(
            onDismissRequest = { showVideoAudioDialog = false },
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
                    text = strings.turboVideoAudioPromptTitle,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val diag = failedState.diagnosisSummary
                    if (!diag.isNullOrBlank()) {
                        TurboDiagnosisBannerChip(text = diag)
                    }
                    Text(
                        text = strings.turboVideoAudioPromptBody(failedState.hasTriedAudioConvert),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVideoAudioDialog = false
                        onOpenVideoAudioPrep()
                    },
                    modifier = Modifier.testTag("turbo_video_audio_confirm_yes")
                ) {
                    Text(strings.turboVideoAudioPromptYes)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showVideoAudioDialog = false },
                    modifier = Modifier.testTag("turbo_video_audio_confirm_no")
                ) {
                    Text(strings.turboRemuxPromptNo)
                }
            }
        )
    }

    if (showCancelConfirmDialog && isActiveStage && state is EncodingState.Running) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = strings.cancelConfirmTitle,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = strings.cancelConfirmBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirmDialog = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("cancel_confirm_yes_button")
                ) {
                    Text(strings.cancelConfirmYes)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelConfirmDialog = false },
                    modifier = Modifier.testTag("cancel_confirm_no_button")
                ) {
                    Text(strings.cancelConfirmKeepRunning)
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

            // Classic live telemetry bar: Time processed / total | speed (e.g. 4.20x) | size (e.g. 15.6mb)
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
 * Keeps 120Hz spring needle reads strictly inside the Draw phase and derived integer odometer state
 * so Compose never recomposes the layout tree on every animation frame.
 */
@Composable
private fun CarSpeedometerGauge(
    percent: Int,
    subLabel: String,
    modifier: Modifier = Modifier
) {
    val clampedTarget = percent.coerceIn(0, 100).toFloat()
    val animatedState = animateFloatAsState(
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

    val primaryArgb = remember(primary) { primary.toArgb() }
    val redlineArgb = remember(redline) { redline.toArgb() }
    val mutedArgb = remember(muted) { muted.toArgb() }
    val sweepColorStops = remember(secondary, primary, redline) {
        arrayOf(
            0.0f to secondary,
            0.45f to primary,
            0.80f to primary,
            1.0f to redline
        )
    }
    val needlePath = remember { Path() }
    val numberPaint = remember(density, ink) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = ink.toArgb()
        }
    }

    val displayedPercent by remember {
        derivedStateOf { animatedState.value.roundToInt().coerceIn(0, 100) }
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
                val animatedValue = animatedState.value
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
                        colorStops = sweepColorStops,
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

                // 4. Automotive tick marks (0..100 using precomputed cos/sin & labels)
                val outerTickRadius = radius * 0.805f
                val labelRadius = radius * 0.625f
                val majorLen = radius * 0.088f
                val mediumLen = radius * 0.058f
                val minorLen = radius * 0.036f
                val majorStroke = 2.3.dp.toPx()
                val mediumStroke = 1.5.dp.toPx()
                val minorStroke = 1.0.dp.toPx()
                val redlineDim = redline.copy(alpha = 0.55f)
                val inkMajor = ink.copy(alpha = 0.62f)
                val mutedMinor = muted.copy(alpha = 0.36f)
                numberPaint.textSize = radius * 0.105f
                val fm = numberPaint.fontMetrics
                val textVerticalOffset = (fm.ascent + fm.descent) / 2f

                for (i in SPEEDOMETER_TICKS.indices) {
                    val tick = SPEEDOMETER_TICKS[i]
                    val isLit = tick.value <= animatedValue + 0.5f

                    val tickLen = when {
                        tick.isMajor -> majorLen
                        tick.isMedium -> mediumLen
                        else -> minorLen
                    }
                    val tickStroke = when {
                        tick.isMajor -> majorStroke
                        tick.isMedium -> mediumStroke
                        else -> minorStroke
                    }
                    val tickColor = when {
                        tick.isRedline && isLit -> redline
                        tick.isRedline -> redlineDim
                        isLit -> primary
                        tick.isMajor -> inkMajor
                        else -> mutedMinor
                    }

                    val innerR = outerTickRadius - tickLen
                    drawLine(
                        color = tickColor,
                        start = Offset(center.x + innerR * tick.cosA, center.y + innerR * tick.sinA),
                        end = Offset(center.x + outerTickRadius * tick.cosA, center.y + outerTickRadius * tick.sinA),
                        strokeWidth = tickStroke,
                        cap = StrokeCap.Round
                    )

                    val label = tick.label
                    if (label != null) {
                        val lx = center.x + labelRadius * tick.cosA
                        val ly = center.y + labelRadius * tick.sinA
                        numberPaint.color = when {
                            tick.isRedline -> redlineArgb
                            isLit -> primaryArgb
                            else -> mutedArgb
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            lx,
                            ly - textVerticalOffset,
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

                // 6. Tapered Automotive Needle + Counterweight (reusing single Path instance)
                val needleAngleDeg = startAngleDeg + totalSweepDeg * progressFraction
                val needleRad = needleAngleDeg * (PI / 180.0)
                val cosA = cos(needleRad).toFloat()
                val sinA = sin(needleRad).toFloat()
                val cosP = -sinA
                val sinP = cosA

                val tipLength = radius * 0.72f
                val tailLength = radius * 0.16f
                val baseHalfWidth = radius * 0.036f
                val tipHalfWidth = radius * 0.008f

                fun fillNeedlePath(offsetY: Float) {
                    needlePath.reset()
                    val cx = center.x
                    val cy = center.y + offsetY
                    val tipX = cx + tipLength * cosA
                    val tipY = cy + tipLength * sinA
                    val tailX = cx - tailLength * cosA
                    val tailY = cy - tailLength * sinA
                    needlePath.moveTo(tipX + tipHalfWidth * cosP, tipY + tipHalfWidth * sinP)
                    needlePath.lineTo(cx + baseHalfWidth * cosP, cy + baseHalfWidth * sinP)
                    needlePath.lineTo(tailX + baseHalfWidth * 0.65f * cosP, tailY + baseHalfWidth * 0.65f * sinP)
                    needlePath.lineTo(tailX - baseHalfWidth * 0.65f * cosP, tailY - baseHalfWidth * 0.65f * sinP)
                    needlePath.lineTo(cx - baseHalfWidth * cosP, cy - baseHalfWidth * sinP)
                    needlePath.lineTo(tipX - tipHalfWidth * cosP, tipY - tipHalfWidth * sinP)
                    needlePath.close()
                }

                // Soft needle drop shadow
                fillNeedlePath(offsetY = 3.dp.toPx())
                drawPath(
                    path = needlePath,
                    color = Color.Black.copy(alpha = 0.14f)
                )
                // Main crimson-espresso racing needle
                fillNeedlePath(offsetY = 0f)
                drawPath(
                    path = needlePath,
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
                    text = strings.formatPercent(displayedPercent),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = if (displayedPercent >= 80) redline else primary,
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
    onEdit: () -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("turbo_open_output_button"),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = strings.actionPlayOutput,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("turbo_edit_output_button"),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = strings.actionEditOutput,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("turbo_share_output_button"),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = strings.actionShare,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TurboDiagnosisBannerChip(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp)
            )
            Text(
                text = text,
                style = MonoLabelStyle.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun TurboFailureWarningCard(
    failed: EncodingState.Failed
) {
    val strings = LocalAppStrings.current
    val titleText = when {
        failed.diagnosisSummary == strings.turbo10BitNotPreservedTitle -> strings.turbo10BitNotPreservedTitle
        failed.hasTriedVideoAudioConvert -> strings.turboAfterVideoAudioFailedTitle
        failed.hasTriedRemux || failed.hasTriedAudioConvert || failed.hasTriedVideoOnlyConvert -> strings.turboAfterRemuxFailedTitle
        failed.isUnsupportedFormat -> strings.turboUnsupportedTitle
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
            if (!failed.diagnosisSummary.isNullOrBlank() && failed.diagnosisSummary != titleText) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(17.dp)
                        )
                        Text(
                            text = failed.diagnosisSummary,
                            style = MonoLabelStyle.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = failed.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (!failed.hint.isNullOrBlank()) {
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
                            text = failed.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
