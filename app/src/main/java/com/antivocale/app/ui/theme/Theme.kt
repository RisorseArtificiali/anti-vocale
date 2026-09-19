package com.antivocale.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Available brand theme options for the app. Each has a dark and a light palette; which one is
 * used is decided by [ThemeMode] (System / Dark / Light) in [AntiVocaleTheme].
 */
enum class ThemeType(val displayName: String) {
    DEFAULT("Default (Indigo)"),
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram")
}

/**
 * How the brand theme's dark/light palette is chosen.
 * - [SYSTEM]: follow the device's system dark setting ([isSystemInDarkTheme]).
 * - [DARK] / [LIGHT]: force that palette regardless of the system setting.
 */
enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light")
}

// ---------------- Default Indigo ----------------
private val DefaultDarkColorScheme = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF4F46E5),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFA5B4FC),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF3730A3),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E1065),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    surfaceDim = Color(0xFF0B1222),
    surfaceBright = Color(0xFF374357),
    surfaceContainerLowest = Color(0xFF0F172A),
    surfaceContainerLow = Color(0xFF1E293B),
    surfaceContainer = Color(0xFF263349),
    surfaceContainerHigh = Color(0xFF2A3A52),
    surfaceContainerHighest = Color(0xFF334155)
)

private val DefaultLightColorScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF4338CA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD5DBE5),
    surfaceBright = Color(0xFFFAFCFF),
    surfaceContainerLowest = Color(0xFFFEFCFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1)
)

// ---------------- WhatsApp (green) ----------------
private val WhatsAppDarkColorScheme = darkColorScheme(
    primary = Color(0xFF25D366),
    onPrimary = Color(0xFF004D25),
    primaryContainer = Color(0xFF128C7E),
    onPrimaryContainer = Color(0xFFDCF8C6),
    secondary = Color(0xFF86EFAC),
    onSecondary = Color(0xFF14532D),
    secondaryContainer = Color(0xFF166534),
    onSecondaryContainer = Color(0xFFDCF8C6),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE8F5E9),
    surface = Color(0xFF121C24),
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = Color(0xFF1E2D3A),
    onSurfaceVariant = Color(0xFFB8D4C8),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2D0A0A),
    surfaceDim = Color(0xFF09121A),
    surfaceBright = Color(0xFF36424C),
    surfaceContainerLowest = Color(0xFF0B141A),
    surfaceContainerLow = Color(0xFF121C24),
    surfaceContainer = Color(0xFF182430),
    surfaceContainerHigh = Color(0xFF1E2D3A),
    surfaceContainerHighest = Color(0xFF243646)
)

private val WhatsAppLightColorScheme = lightColorScheme(
    primary = Color(0xFF128C7E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCF8C6),
    onPrimaryContainer = Color(0xFF004D25),
    secondary = Color(0xFF25D366),
    onSecondary = Color(0xFF003D1F),
    secondaryContainer = Color(0xFFDCF8C6),
    onSecondaryContainer = Color(0xFF004D25),
    tertiary = Color(0xFF16A34A),
    onTertiary = Color(0xFF052E16),
    background = Color(0xFFE8F5E9),
    onBackground = Color(0xFF0B141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2E23),
    surfaceVariant = Color(0xFFC8E6C9),
    onSurfaceVariant = Color(0xFF3B5240),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFCBE8CD),
    surfaceBright = Color(0xFFFCFDF6),
    surfaceContainerLowest = Color(0xFFFEFCFF),
    surfaceContainerLow = Color(0xFFF4FAF4),
    surfaceContainer = Color(0xFFE8F5E9),
    surfaceContainerHigh = Color(0xFFDCEFDD),
    surfaceContainerHighest = Color(0xFFC8E6C9)
)

// ---------------- Telegram (blue) ----------------
private val TelegramDarkColorScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF0D47A1),
    primaryContainer = Color(0xFF1976D2),
    onPrimaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF0D47A1),
    secondaryContainer = Color(0xFF1565C0),
    onSecondaryContainer = Color(0xFFE3F2FD),
    tertiary = Color(0xFF42A5F5),
    onTertiary = Color(0xFF0A2E52),
    background = Color(0xFF0A0E14),
    onBackground = Color(0xFFE3F2FD),
    surface = Color(0xFF111820),
    onSurface = Color(0xFFE3F2FD),
    surfaceVariant = Color(0xFF1C2733),
    onSurfaceVariant = Color(0xFFB0BEC5),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2D0A0A),
    surfaceDim = Color(0xFF070B10),
    surfaceBright = Color(0xFF333D48),
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF111820),
    surfaceContainer = Color(0xFF161F2A),
    surfaceContainerHigh = Color(0xFF1C2733),
    surfaceContainerHighest = Color(0xFF232F3D)
)

private val TelegramLightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F2FD),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF42A5F5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3F2FD),
    onSecondaryContainer = Color(0xFF0D47A1),
    tertiary = Color(0xFF1565C0),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFEFF5FB),
    onBackground = Color(0xFF0A0E14),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17202A),
    surfaceVariant = Color(0xFFBBDEFB),
    onSurfaceVariant = Color(0xFF37474F),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD3E1EC),
    surfaceBright = Color(0xFFFDFEFE),
    surfaceContainerLowest = Color(0xFFFCFDFD),
    surfaceContainerLow = Color(0xFFF7FAFD),
    surfaceContainer = Color(0xFFEFF5FB),
    surfaceContainerHigh = Color(0xFFE3EEF8),
    surfaceContainerHighest = Color(0xFFBBDEFB)
)

/**
 * TASK-576: the app-level text size. SYSTEM applies no extra scaling (sp
 * already follows the OS font size); the other steps multiply every
 * typography fontSize/lineHeight on top of the system setting.
 */
enum class TextScale(val multiplier: Float, val nameRes: Int) {
    SYSTEM(1.0f, com.antivocale.app.R.string.text_scale_system),
    SMALL(0.9f, com.antivocale.app.R.string.text_scale_small),
    LARGE(1.15f, com.antivocale.app.R.string.text_scale_large),
    XLARGE(1.3f, com.antivocale.app.R.string.text_scale_xlarge),
}

private fun TextStyle.scaledFont(f: Float): TextStyle =
    copy(fontSize = fontSize * f, lineHeight = lineHeight * f)

private fun scaledTypography(f: Float): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.scaledFont(f),
        displayMedium = t.displayMedium.scaledFont(f),
        displaySmall = t.displaySmall.scaledFont(f),
        headlineLarge = t.headlineLarge.scaledFont(f),
        headlineMedium = t.headlineMedium.scaledFont(f),
        headlineSmall = t.headlineSmall.scaledFont(f),
        titleLarge = t.titleLarge.scaledFont(f),
        titleMedium = t.titleMedium.scaledFont(f),
        titleSmall = t.titleSmall.scaledFont(f),
        bodyLarge = t.bodyLarge.scaledFont(f),
        bodyMedium = t.bodyMedium.scaledFont(f),
        bodySmall = t.bodySmall.scaledFont(f),
        labelLarge = t.labelLarge.scaledFont(f),
        labelMedium = t.labelMedium.scaledFont(f),
        labelSmall = t.labelSmall.scaledFont(f),
    )
}

@Composable
fun AntiVocaleTheme(
    brand: ThemeType = ThemeType.DEFAULT,
    mode: ThemeMode = ThemeMode.SYSTEM,
    textScale: TextScale = TextScale.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when (brand) {
        ThemeType.DEFAULT -> if (isDark) DefaultDarkColorScheme else DefaultLightColorScheme
        ThemeType.WHATSAPP -> if (isDark) WhatsAppDarkColorScheme else WhatsAppLightColorScheme
        ThemeType.TELEGRAM -> if (isDark) TelegramDarkColorScheme else TelegramLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (textScale == TextScale.SYSTEM) Typography() else scaledTypography(textScale.multiplier),
        content = content
    )
}
