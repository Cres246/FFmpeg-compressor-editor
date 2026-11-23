package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.AppLanguage
import com.example.domain.AppLanguageController
import com.example.domain.CompletedOutput
import com.example.domain.EncodingState
import com.example.domain.LanguagePreference
import com.example.domain.LocalAppStrings
import com.example.domain.ModeCategory
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.isHardwareTurboState
import com.example.domain.stringsFor
import com.example.ui.MainViewModel
import com.example.ui.components.MediaHeroCard
import com.example.ui.components.ModeCategoryHeader
import com.example.ui.components.OtherModesList
import com.example.ui.components.TargetSizeModeRow
import com.example.ui.components.TrimFeatureCard
import com.example.ui.screens.HistorySheet
import com.example.ui.screens.ModeConfigScreen
import com.example.ui.screens.ProcessingScreen
import com.example.ui.screens.TurboProcessingScreen
import com.example.ui.theme.FFLabTheme
import com.example.ui.theme.LocalExtendedColors
import com.example.ui.theme.MonoLabelStyle
import com.example.ui.theme.PlayfairDisplayFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily

private enum class AppScreenStage(val depth: Int) {
    HOME(0),
    CONFIG(1),
    PROCESSING(2),
    TURBO_PROCESSING(3)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isIgnoringBatteryOptimizations by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguageController.init(applicationContext)
        isIgnoringBatteryOptimizations = checkIgnoringBatteryOptimizations()
        enableEdgeToEdge()
        requestHighestRefreshRate()
        handleIncomingIntent(intent)

        setContent {
            FFLabTheme {
                FFLabAppRoot(
                    viewModel = viewModel,
                    isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                    onRequestIgnoreBatteryOptimizations = ::requestIgnoreBatteryOptimizations,
                    onOpenOutput = ::openCompletedOutput,
                    onShareOutput = ::shareCompletedOutput,
                    onPlaySourceMedia = ::openSourceMedia
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLanguageController.init(applicationContext)
        isIgnoringBatteryOptimizations = checkIgnoringBatteryOptimizations()
        requestHighestRefreshRate()
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching {
            startActivity(directIntent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            requestHighestRefreshRate()
        }
    }

    /**
     * Explicitly selects the highest refresh-rate Display.Mode matching the current physical
     * resolution (e.g. 120Hz on Honor 400 / MagicOS) so Android does not throttle Compose to 60Hz.
     */
    private fun requestHighestRefreshRate() {
        runCatching {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val currentMode = activeDisplay.mode
            val bestMode = activeDisplay.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: activeDisplay.supportedModes.maxByOrNull { it.refreshRate }

            if (bestMode != null) {
                val lp = window.attributes
                if (lp.preferredDisplayModeId != bestMode.modeId || lp.preferredRefreshRate != bestMode.refreshRate) {
                    lp.preferredDisplayModeId = bestMode.modeId
                    lp.preferredRefreshRate = bestMode.refreshRate
                    window.attributes = lp
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.decorView.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val incomingUri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (incomingUri != null) {
            viewModel.onMediaSelected(incomingUri)
        }
    }

    private fun openCompletedOutput(output: CompletedOutput) {
        val s = AppLanguageController.currentStrings()
        val uri = Uri.parse(output.contentUriString)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, output.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(viewIntent, s.actionOpen))
        }.onFailure {
            Toast.makeText(this, s.toastNoAppToOpenFile, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCompletedOutput(output: CompletedOutput) {
        val s = AppLanguageController.currentStrings()
        val uri = Uri.parse(output.contentUriString)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = output.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, s.actionShare))
        }.onFailure {
            Toast.makeText(this, s.toastShareFailed, Toast.LENGTH_SHORT).show()
        }
    }
    private fun openSourceMedia(media: com.example.domain.MediaInfo) {
        val s = AppLanguageController.currentStrings()
        val mime = if (media.hasVideo) {
            com.example.domain.FfmpegMath.resolveMimeType(media.fileName).takeIf { it.startsWith("video/") }
                ?: "video/*"
        } else {
            "audio/*"
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(media.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(viewIntent, s.chooserPlayVideoWith))
        }.onFailure {
            Toast.makeText(this, s.toastNoAppToPlayVideo, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun FFLabAppRoot(
    viewModel: MainViewModel,
    isIgnoringBatteryOptimizations: Boolean,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onOpenOutput: (CompletedOutput) -> Unit,
    onShareOutput: (CompletedOutput) -> Unit,
    onPlaySourceMedia: (com.example.domain.MediaInfo) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val encodingState by viewModel.encodingState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val languagePreference by viewModel.languagePreference.collectAsStateWithLifecycle()
    val strings = remember(appLanguage) { stringsFor(appLanguage) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var batteryDialogDismissed by rememberSaveable { mutableStateOf(false) }

    // Zero-permission Android Photo Picker for gallery videos
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onMediaSelected(uri)
        }
    }

    // Zero-permission Storage Access Framework picker for MKV / huge movies / audio
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onMediaSelected(uri)
        }
    }

    // Optional notification permission for API 33+ progress / Magic Capsule notification
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.startEncoding()
    }

    fun launchEncodingWithNotifCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startEncoding()
        }
    }

    val targetSizeModes = ProcessingMode.entries.filter { it.category == ModeCategory.TARGET_SIZE }
    val otherModes = ProcessingMode.entries.filter { it.category == ModeCategory.OTHER }

    val currentStage = when {
        encodingState.isHardwareTurboState() -> AppScreenStage.TURBO_PROCESSING
        encodingState !is EncodingState.Idle -> AppScreenStage.PROCESSING
        uiState.activeConfig != null && uiState.mediaInfo != null -> AppScreenStage.CONFIG
        else -> AppScreenStage.HOME
    }

    // Keep the last rendered screen payload alive while exiting so vertical slide transitions never collapse or flash
    var cachedConfig by remember { mutableStateOf<ModeConfigState?>(null) }
    if (uiState.activeConfig != null) {
        cachedConfig = uiState.activeConfig
    }
    var cachedStandardEncoding by remember { mutableStateOf<EncodingState>(EncodingState.Idle) }
    if (encodingState !is EncodingState.Idle && !encodingState.isHardwareTurboState()) {
        cachedStandardEncoding = encodingState
    }
    var cachedTurboEncoding by remember { mutableStateOf<EncodingState>(EncodingState.Idle) }
    if (encodingState.isHardwareTurboState()) {
        cachedTurboEncoding = encodingState
    }

    CompositionLocalProvider(LocalAppStrings provides strings) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            // 120Hz vertical push transition (0.88s, slow start -> smooth acceleration -> gentle stop):
            // Forward (selecting a mode / starting encode): new screen pushes up from bottom (+H -> 0)
            // while current screen is pushed up off the top (0 -> -H) in lockstep.
            // Backward (going back / cancelling): previous screen pushes down from top (-H -> 0)
            // while exiting screen is pushed down off the bottom (0 -> +H) in lockstep.
            val verticalPushEasing = remember { CubicBezierEasing(0.68f, 0.0f, 0.32f, 1.0f) }
            val verticalPushSpec = remember(verticalPushEasing) {
                tween<IntOffset>(durationMillis = 880, easing = verticalPushEasing)
            }
            AnimatedContent(
                targetState = currentStage,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    if (forward) {
                        ContentTransform(
                            targetContentEnter = slideInVertically(
                                animationSpec = verticalPushSpec,
                                initialOffsetY = { fullHeight -> fullHeight }
                            ),
                            initialContentExit = slideOutVertically(
                                animationSpec = verticalPushSpec,
                                targetOffsetY = { fullHeight -> -fullHeight }
                            ),
                            targetContentZIndex = targetState.depth.toFloat(),
                            sizeTransform = null
                        )
                    } else {
                        ContentTransform(
                            targetContentEnter = slideInVertically(
                                animationSpec = verticalPushSpec,
                                initialOffsetY = { fullHeight -> -fullHeight }
                            ),
                            initialContentExit = slideOutVertically(
                                animationSpec = verticalPushSpec,
                                targetOffsetY = { fullHeight -> fullHeight }
                            ),
                            targetContentZIndex = targetState.depth.toFloat(),
                            sizeTransform = null
                        )
                    }
                },
                label = "fflab_screen_vertical_push"
            ) { stage ->
                when (stage) {
                    AppScreenStage.TURBO_PROCESSING -> {
                        val renderTurbo = if (encodingState.isHardwareTurboState()) encodingState else cachedTurboEncoding
                        TurboProcessingScreen(
                            state = renderTurbo,
                            isActiveStage = currentStage == AppScreenStage.TURBO_PROCESSING,
                            onCancel = viewModel::cancelEncoding,
                            onOpenOutput = onOpenOutput,
                            onShareOutput = onShareOutput,
                            onDoneReturnHome = { viewModel.dismissEncodingResult(returnToWorkbench = true) },
                            onTryRemuxAndRetry = viewModel::tryRemuxThenRetryTurbo,
                            onFallbackToFfmpeg = { viewModel.fallbackFromTurboToFfmpeg(useFastPreset = false) },
                            onFallbackToFfmpegFast = { viewModel.fallbackFromTurboToFfmpeg(useFastPreset = true) }
                        )
                    }

                    AppScreenStage.PROCESSING -> {
                        val renderEncoding = if (encodingState !is EncodingState.Idle && !encodingState.isHardwareTurboState()) {
                            encodingState
                        } else {
                            cachedStandardEncoding
                        }
                        ProcessingScreen(
                            state = renderEncoding,
                            isActiveStage = currentStage == AppScreenStage.PROCESSING,
                            onCancel = viewModel::cancelEncoding,
                            onSwitchToTurbo = viewModel::switchCurrentJobToTurbo,
                            onOpenOutput = onOpenOutput,
                            onShareOutput = onShareOutput,
                            onDoneReturnHome = { viewModel.dismissEncodingResult(returnToWorkbench = true) },
                            onRetryEdit = { viewModel.dismissEncodingResult(returnToWorkbench = false) }
                        )
                    }

                    AppScreenStage.CONFIG -> {
                        val activeCfg = uiState.activeConfig ?: cachedConfig
                        val media = uiState.mediaInfo
                        if (activeCfg != null && media != null) {
                            ModeConfigScreen(
                                media = media,
                                config = activeCfg,
                                capabilities = uiState.capabilities,
                                validationError = uiState.validationError,
                                viewModel = viewModel,
                                isActiveStage = currentStage == AppScreenStage.CONFIG,
                                onPlaySourceMedia = { onPlaySourceMedia(media) },
                                onStartClick = { launchEncodingWithNotifCheck() }
                            )
                        }
                    }

                    AppScreenStage.HOME -> {
                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            topBar = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { showCreditsDialog = true }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                            .testTag("ffmpeg_credits_button"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Info,
                                            contentDescription = strings.creditsInfoCd,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            text = stringResource(R.string.app_name),
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box {
                                            IconButton(
                                                onClick = { showLanguageMenu = true },
                                                modifier = Modifier.testTag("language_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Language,
                                                    contentDescription = strings.languageSelectorCd,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(21.dp)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showLanguageMenu,
                                                onDismissRequest = { showLanguageMenu = false },
                                                containerColor = MaterialTheme.colorScheme.surface
                                            ) {
                                                val systemDetected = remember(showLanguageMenu) {
                                                    AppLanguageController.detectSystemLanguage()
                                                }
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = "${strings.languageAutoSystem} · ${systemDetected.code.uppercase()}",
                                                            fontWeight = if (languagePreference == LanguagePreference.SYSTEM) FontWeight.SemiBold else FontWeight.Normal,
                                                            color = if (languagePreference == LanguagePreference.SYSTEM) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            }
                                                        )
                                                    },
                                                    trailingIcon = if (languagePreference == LanguagePreference.SYSTEM) {
                                                        {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Check,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    } else null,
                                                    onClick = {
                                                        showLanguageMenu = false
                                                        viewModel.setLanguagePreference(LanguagePreference.SYSTEM)
                                                    },
                                                    modifier = Modifier.testTag("language_option_system")
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = AppLanguage.TR.nativeName,
                                                            fontWeight = if (languagePreference == LanguagePreference.TR) FontWeight.SemiBold else FontWeight.Normal,
                                                            color = if (languagePreference == LanguagePreference.TR) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            }
                                                        )
                                                    },
                                                    trailingIcon = if (languagePreference == LanguagePreference.TR) {
                                                        {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Check,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    } else null,
                                                    onClick = {
                                                        showLanguageMenu = false
                                                        viewModel.setLanguagePreference(LanguagePreference.TR)
                                                    },
                                                    modifier = Modifier.testTag("language_option_tr")
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = AppLanguage.EN.nativeName,
                                                            fontWeight = if (languagePreference == LanguagePreference.EN) FontWeight.SemiBold else FontWeight.Normal,
                                                            color = if (languagePreference == LanguagePreference.EN) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            }
                                                        )
                                                    },
                                                    trailingIcon = if (languagePreference == LanguagePreference.EN) {
                                                        {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Check,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    } else null,
                                                    onClick = {
                                                        showLanguageMenu = false
                                                        viewModel.setLanguagePreference(LanguagePreference.EN)
                                                    },
                                                    modifier = Modifier.testTag("language_option_en")
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = { viewModel.setShowHistorySheet(true) },
                                            modifier = Modifier.testTag("history_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.History,
                                                contentDescription = strings.historyTitle,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(21.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                MediaHeroCard(
                                    mediaInfo = uiState.mediaInfo,
                                    isProbing = uiState.isProbing,
                                    probeError = uiState.probeError,
                                    onPickGalleryVideo = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                    onPickAnyFile = {
                                        documentPickerLauncher.launch(
                                            arrayOf("video/*", "audio/*", "application/octet-stream")
                                        )
                                    }
                                )

                                // Grup 1: Hedef Boyut (Discord, WhatsApp, Özel Boyut)
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    ModeCategoryHeader(category = ModeCategory.TARGET_SIZE)
                                    TargetSizeModeRow(
                                        modes = targetSizeModes,
                                        mediaInfo = uiState.mediaInfo,
                                        onSelectMode = viewModel::selectMode
                                    )
                                }

                                // Grup 2: Videoyu Kes
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    ModeCategoryHeader(category = ModeCategory.TRIM)
                                    TrimFeatureCard(
                                        mode = ProcessingMode.TRIM_VIDEO,
                                        mediaInfo = uiState.mediaInfo,
                                        onClick = { viewModel.selectMode(ProcessingMode.TRIM_VIDEO) }
                                    )
                                }

                                // Grup 3: Diğer İşlemler (6 mod alt alta, özel sırada)
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    ModeCategoryHeader(category = ModeCategory.OTHER)
                                    OtherModesList(
                                        modes = otherModes,
                                        mediaInfo = uiState.mediaInfo,
                                        onSelectMode = viewModel::selectMode
                                    )
                                }

                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showHistorySheet) {
        HistorySheet(
            history = history,
            onDismiss = { viewModel.setShowHistorySheet(false) },
            onOpen = {
                viewModel.setShowHistorySheet(false)
                onOpenOutput(it)
            },
            onShare = {
                viewModel.setShowHistorySheet(false)
                onShareOutput(it)
            },
            onClearAll = viewModel::clearHistory
        )
    }

    if (!isIgnoringBatteryOptimizations && !batteryDialogDismissed) {
        AlertDialog(
            onDismissRequest = { batteryDialogDismissed = true },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = strings.batteryOptDialogTitle,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = strings.batteryOptDialogBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        batteryDialogDismissed = true
                        onRequestIgnoreBatteryOptimizations()
                    },
                    modifier = Modifier.testTag("battery_opt_allow_button")
                ) {
                    Text(strings.batteryOptAllowButton)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { batteryDialogDismissed = true },
                    modifier = Modifier.testTag("battery_opt_later_button")
                ) {
                    Text(strings.batteryOptLaterButton)
                }
            }
        )
    }

    if (showCreditsDialog) {
        OpenSourceCreditsDialog(
            onDismiss = { showCreditsDialog = false }
        )
    }
    }
}

@Composable
private fun OpenSourceCreditsDialog(
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val extColors = LocalExtendedColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.creditsDialogTitle,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = PlayfairDisplayFontFamily,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 23.sp,
                        lineHeight = 28.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.creditsDialogSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CreditEntryCard(
                    sectionLabel = strings.creditsCoreSectionLabel,
                    libraryName = "FFmpeg",
                    authorLine = strings.creditsCoreCreators,
                    extraNote = strings.creditsCoreEncoders,
                    borderColor = extColors.subtleBorder
                )
                CreditEntryCard(
                    sectionLabel = strings.creditsOriginalLibSectionLabel,
                    libraryName = "FFmpegKit",
                    packageTag = "arthenica/ffmpeg-kit",
                    authorLine = strings.creditsOriginalLibCreator,
                    borderColor = extColors.subtleBorder
                )
                CreditEntryCard(
                    sectionLabel = strings.creditsForkSectionLabel,
                    libraryName = "FFmpegKit Maintained (Full GPL)",
                    packageTag = "dev.ffmpegkit-maintained · v8.1.9",
                    authorLine = strings.creditsForkMaintainer,
                    borderColor = extColors.subtleBorder
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("credits_close_button")
            ) {
                Text(
                    text = strings.actionClose,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

@Composable
private fun CreditEntryCard(
    sectionLabel: String,
    libraryName: String,
    authorLine: String,
    borderColor: androidx.compose.ui.graphics.Color,
    packageTag: String? = null,
    extraNote: String? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = sectionLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = PlayfairDisplayFontFamily,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                ),
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = libraryName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!packageTag.isNullOrBlank()) {
                Text(
                    text = packageTag,
                    style = MonoLabelStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                thickness = 0.8.dp,
                color = borderColor
            )
            Text(
                text = authorLine,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!extraNote.isNullOrBlank()) {
                Text(
                    text = extraNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
