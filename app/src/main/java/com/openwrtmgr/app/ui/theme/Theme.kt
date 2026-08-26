package com.openwrtmgr.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OpenWrt-blue brand identity: a full tonal palette derived from the app's original accent color
 * rather than a single hardcoded `primary`, so surfaces/containers/outlines all relate correctly
 * (Material3's tonal-palette contract) instead of falling back to stock Material purple everywhere
 * else. Dynamic color (Android 12+ wallpaper theming) is still offered, but opt-in and off by
 * default — a router-admin app benefits more from a stable, recognizable identity than from
 * matching whatever wallpaper the phone has.
 */
private val BrandLight = lightColorScheme(
    primary = Color(0xFF2E5C8A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF3F6088),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001C38),
    tertiary = Color(0xFF1C6D63),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA0F1E2),
    onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAF9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFA4C9FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF17457F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFA7C8FA),
    onSecondary = Color(0xFF063060),
    secondaryContainer = Color(0xFF254876),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = Color(0xFF84D5C7),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005046),
    onTertiaryContainer = Color(0xFFA0F1E2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    )
}

/**
 * Status colors that stay meaningful in both themes — plain `Color(0xFF2E7D32)` green reads fine
 * on a white card and badly on a near-black one. Exposed via [LocalStatusColors] instead of
 * `MaterialTheme.colorScheme` because Material3 has no built-in "success" role.
 */
data class StatusColorPalette(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val neutral: Color,
)

private val LightStatusColors = StatusColorPalette(
    success = Color(0xFF2E7D32),
    warning = Color(0xFFB25E00),
    danger = Color(0xFFBA1A1A),
    neutral = Color(0xFF6B7076),
)

private val DarkStatusColors = StatusColorPalette(
    success = Color(0xFF7DDB80),
    warning = Color(0xFFFFB870),
    danger = Color(0xFFFFB4AB),
    neutral = Color(0xFF9CA1A8),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

@Composable
fun OpenWrtManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}
