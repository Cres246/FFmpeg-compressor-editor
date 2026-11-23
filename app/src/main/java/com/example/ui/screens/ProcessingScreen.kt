package com.example.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.CompletedOutput
import com.example.domain.EncodingState
import com.example.domain.FfmpegMath
import com.example.domain.LocalAppStrings
import com.example.service.CpuPerformanceBooster
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoLabelStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    state: EncodingState,
    isActiveStage: Boolean = true,
    onCancel: () -> Unit,
    onOpenOutput: (CompletedOutput) -> Unit,
    onShareOutput: (CompletedOutput) -> Unit,
    onDoneReturnHome: () -> Unit,
    onRetryEdit: () -> Unit
) {
    // Only intercept back while this screen is actually the active foreground stage
    BackHandler(enabled = isActiveStage) {
        if (state !is EncodingState.Running) {
            onDoneReturnHome()
        }
    }

    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    var showLogs by remember { mutableStateOf(state is EncodingState.Failed) }

    val topTitle = when (state) {
        is EncodingState.Running -> state.modeTitle
        is EncodingState.Success -> state.modeTitle
        is EncodingState.Failed -> state.modeTitle
        EncodingState.Idle -> "FFmpeg"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = topTitle, style = MaterialTheme.typography.titleLarge)
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
                                    .testTag("cancel_encoding_button"),
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
                                    .testTag("new_task_button"),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(strings.actionNewTask)
                            }
                        }

                        is EncodingState.Failed -> {
                            OutlinedButton(
                                onClick = onDoneReturnHome,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("failed_home_button"),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(strings.actionHome)
                            }
                            Button(
                                onClick = onRetryEdit,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("failed_retry_button"),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.actionBackToSettings)
                            }
                        }

                        EncodingState.Idle -> Unit
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (state) {
                is EncodingState.Running -> {
                    item {
                        RunningMonitorCard(state = state)
                    }
                    item {
                        LogConsoleCard(
                            logs = state.logs,
                            expanded = showLogs,
                            onToggle = { showLogs = !showLogs }
                        )
                    }
                }

                is EncodingState.Success -> {
                    item {
                        SuccessSummaryBanner(summary = state.summaryMessage)
                    }
                    items(state.outputs, key = { it.id }) { output ->
                        CompletedOutputCard(
                            output = output,
                            onOpen = { onOpenOutput(output) },
                            onShare = { onShareOutput(output) }
                        )
                    }
                    if (state.logs.isNotEmpty()) {
                        item {
                            LogConsoleCard(
                                logs = state.logs,
                                expanded = showLogs,
                                onToggle = { showLogs = !showLogs }
                            )
                        }
                    }
                }

                is EncodingState.Failed -> {
                    item {
                        FailureBannerCard(
                            errorMessage = state.errorMessage,
                            hint = state.hint,
                            wasCancelled = state.wasCancelled
                        )
                    }
                    if (state.logs.isNotEmpty()) {
                        item {
                            LogConsoleCard(
                                logs = state.logs,
                                expanded = showLogs,
                                onToggle = { showLogs = !showLogs }
                            )
                        }
                    }
                }

                EncodingState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RunningMonitorCard(state: EncodingState.Running) {
    val primary = MaterialTheme.colorScheme.primary
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
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
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, primary.copy(alpha = 0.4f)),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = CircleShape,
                    color = primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = if (state.totalPasses > 1) {
                            strings.passBadge(state.passIndex, state.totalPasses)
                        } else {
                            strings.processingBadge
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Text(
                    text = state.percent?.let { strings.formatPercent(it) } ?: "...",
                    style = MaterialTheme.typography.displaySmall,
                    color = primary
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = state.summaryBanner,
                style = MonoLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))

            if (state.percent != null) {
                LinearProgressIndicator(
                    progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape),
                    color = primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape),
                    color = primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeCap = StrokeCap.Round
                )
            }

            Spacer(Modifier.height(16.dp))

            // Single-line live telemetry bar (percentage is already prominently shown at top-right)
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            val cpuSummary = remember(strings) { CpuPerformanceBooster.describeCpuBoostSummary(strings) }
            Text(
                text = "$cpuSummary · ${strings.wakeLockActiveSuffix}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (Build.VERSION.SDK_INT >= 36) {
                val context = LocalContext.current
                val canPromote = remember(state.percent, state.passIndex) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    runCatching { nm?.canPostPromotedNotifications() ?: true }.getOrDefault(true)
                }
                if (!canPromote) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val promoIntent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            runCatching { context.startActivity(promoIntent) }.onFailure {
                                val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                runCatching { context.startActivity(fallback) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("enable_magic_capsule_button"),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = strings.enableMagicCapsuleButton,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessSummaryBanner(summary: String) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
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
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(extColors.lossless.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = strings.successCd,
                    tint = extColors.lossless,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = strings.successTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = extColors.onLosslessContainer
                )
                Text(
                    text = summary,
                    style = MonoLabelStyle,
                    color = extColors.onLosslessContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(2.dp))
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
private fun CompletedOutputCard(
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
                        .height(46.dp)
                        .testTag("open_output_button"),
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
                        .height(46.dp)
                        .testTag("share_output_button"),
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
private fun FailureBannerCard(
    errorMessage: String,
    hint: String?,
    wasCancelled: Boolean
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (wasCancelled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(
            1.dp,
            if (wasCancelled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (wasCancelled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (wasCancelled) strings.failedCancelledTitle else strings.failedErrorTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (wasCancelled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (wasCancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
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

@Composable
private fun LogConsoleCard(
    logs: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val extColors = LocalExtendedColors.current
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extColors.subtleBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = strings.logConsoleHeader(logs.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                val consoleScroll = rememberScrollState()
                LaunchedEffect(logs.size) {
                    consoleScroll.animateScrollTo(consoleScroll.maxValue)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, extColors.subtleBorder)
                ) {
                    Text(
                        text = if (logs.isEmpty()) {
                            strings.logConsoleEmpty
                        } else {
                            logs.takeLast(80).joinToString("\n")
                        },
                        style = MonoCodeStyle.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 260.dp)
                            .verticalScroll(consoleScroll)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}
