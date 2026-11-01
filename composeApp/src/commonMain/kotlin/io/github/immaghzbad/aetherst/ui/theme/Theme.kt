package io.github.immaghzbad.aetherst.shared.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0A6CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2FF),
    onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF5A5A63),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF1A1A1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1F),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF5A5A63),
    outline = Color(0xFFDCDCE2),
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    isRtl: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dynamicDarkScheme = darkColorScheme(
        primary = AppPalette.accent,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = AppPalette.accentVariant,
        onPrimaryContainer = Color(0xFFD6E2FF),
        secondary = AppPalette.textSecondary,
        background = AppPalette.surfaceSunken,
        onBackground = AppPalette.textPrimary,
        surface = AppPalette.surfaceRaised,
        onSurface = AppPalette.textPrimary,
        surfaceVariant = AppPalette.groupBg,
        onSurfaceVariant = AppPalette.textSecondary,
        outline = AppPalette.divider,
    )
    val colorScheme = if (darkTheme) dynamicDarkScheme else LightColorScheme
    val appColors = if (darkTheme) darkAppColors else lightAppColors

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalAppColors provides appColors,
    ) {
        BoxWithConstraints {
            val scale = (maxWidth.value / 411f).coerceIn(0.7f, 1.1f)
            CompositionLocalProvider(LocalScaleFactor provides scale) {
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = appTypography(isRtl = isRtl),
                    content = content,
                )
            }
        }
    }
}
