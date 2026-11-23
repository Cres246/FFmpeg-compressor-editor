package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Immutable
data class ExtendedColors(
    val lossless: Color,
    val losslessContainer: Color,
    val onLosslessContainer: Color,
    val keyframe: Color,
    val keyframeContainer: Color,
    val onKeyframeContainer: Color,
    val subtleBorder: Color
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        lossless = AntiqueSage,
        losslessContainer = AntiqueSageContainer,
        onLosslessContainer = OnAntiqueSageContainer,
        keyframe = CelloAmber,
        keyframeContainer = CelloAmberContainer,
        onKeyframeContainer = OnCelloAmberContainer,
        subtleBorder = WarmSubtleBorder
    )
}

private val DaylightColorScheme = lightColorScheme(
    primary = CelloBrown,
    onPrimary = IvorySurface,
    primaryContainer = CelloBrownContainer,
    onPrimaryContainer = OnCelloBrownContainer,
    secondary = CelloAmber,
    onSecondary = IvorySurface,
    secondaryContainer = CelloAmberContainer,
    onSecondaryContainer = OnCelloAmberContainer,
    tertiary = AntiqueSage,
    onTertiary = IvorySurface,
    tertiaryContainer = AntiqueSageContainer,
    onTertiaryContainer = OnAntiqueSageContainer,
    background = CreamBg,
    onBackground = EspressoInk,
    surface = IvorySurface,
    onSurface = EspressoInk,
    surfaceVariant = LinenSurface,
    onSurfaceVariant = WalnutMuted,
    surfaceContainerLowest = IvorySurface,
    surfaceContainerLow = CreamBg,
    surfaceContainer = LinenSurface,
    surfaceContainerHigh = ParchmentSurface,
    surfaceContainerHighest = ParchmentSurface,
    outline = WarmOutline,
    outlineVariant = WarmSubtleBorder,
    error = SiennaError,
    onError = IvorySurface,
    errorContainer = SiennaErrorContainer,
    onErrorContainer = OnSiennaErrorContainer
)

private val FFLabShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun FFLabTheme(
    content: @Composable () -> Unit
) {
    val extendedColors = ExtendedColors(
        lossless = AntiqueSage,
        losslessContainer = AntiqueSageContainer,
        onLosslessContainer = OnAntiqueSageContainer,
        keyframe = CelloAmber,
        keyframeContainer = CelloAmberContainer,
        onKeyframeContainer = OnCelloAmberContainer,
        subtleBorder = WarmSubtleBorder
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = DaylightColorScheme,
            typography = FFLabTypography,
            shapes = FFLabShapes,
            content = content
        )
    }
}
