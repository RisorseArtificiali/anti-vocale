package com.localai.bridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Available theme options for the app.
 */
enum class ThemeType(val displayName: String) {
    DEFAULT("Default (Indigo)"),
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram")
}

// Default Indigo theme
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
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A)
)

// WhatsApp-inspired dark theme (green accents)
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
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE8F5E9),
    surface = Color(0xFF121C24),
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = Color(0xFF1E2D3A),
    onSurfaceVariant = Color(0xFFB8D4C8),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2D0A0A)
)

// Telegram-inspired dark theme (blue accents)
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
    background = Color(0xFF0A0E14),
    onBackground = Color(0xFFE3F2FD),
    surface = Color(0xFF111820),
    onSurface = Color(0xFFE3F2FD),
    surfaceVariant = Color(0xFF1C2733),
    onSurfaceVariant = Color(0xFFB0BEC5),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2D0A0A)
)

@Composable
fun LocalAITaskerBridgeTheme(
    theme: ThemeType = ThemeType.DEFAULT,
    content: @Composable () -> Unit
) {
    val colorScheme = when (theme) {
        ThemeType.DEFAULT -> DefaultDarkColorScheme
        ThemeType.WHATSAPP -> WhatsAppDarkColorScheme
        ThemeType.TELEGRAM -> TelegramDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
