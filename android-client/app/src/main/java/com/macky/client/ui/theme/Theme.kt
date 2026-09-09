package com.macky.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Technical Dark Design System (Tailscale / Linear / Raycast inspired)
val DarkBg = Color(0xFF0A0E14)
val CardBg = Color(0xFF151A24)
val CardBgElevated = Color(0xFF1A212E)
val BorderColor = Color(0xFF202633)
val BorderColorSubtle = Color(0xFF191F2B)

val AccentCyan = Color(0xFF4DD0FF)        // Electric cyan primary interactive accent ONLY
val AccentBlue = AccentCyan               // Compatibility alias
val SuccessGreen = Color(0xFF10B981)      // Strictly for connected / active states
val WarningYellow = Color(0xFFF59E0B)     // Transition / connecting states
val DangerRed = Color(0xFFEF4444)         // Destructive / disconnect / error states

val TextPrimary = Color(0xFFF0F6FC)       // Crisp high-contrast headings & values
val TextSecondary = Color(0xFF8B949E)     // Technical secondary metadata
val TextMuted = Color(0xFF4F5968)         // Subtle captions & disabled states

val MonoFont = FontFamily.Monospace
val SansFont = FontFamily.Default

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color.Black,
    secondary = SuccessGreen,
    onSecondary = Color.Black,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    surfaceVariant = BorderColor,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    onError = Color.White
)

@Composable
fun AcuityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

@Composable
fun MackyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    AcuityTheme(darkTheme = darkTheme, content = content)
}
