package com.jpdjdev.digitalvolumecontrol.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Modern glassmorphism color scheme
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6366F1), // Indigo
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF10B981), // Emerald
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFF59E0B), // Amber
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF92400E),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1F2937),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    scrim = Color(0x80000000),
    inverseSurface = Color(0xFF1F2937),
    inverseOnSurface = Color(0xFFF9FAFB),
    inversePrimary = Color(0xFF818CF8)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF818CF8), // Light indigo
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF34D399), // Light emerald
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF047857),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFFBBF24), // Light amber
    onTertiary = Color(0xFF92400E),
    tertiaryContainer = Color(0xFFD97706),
    onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFF87171),
    onError = Color(0xFF991B1B),
    errorContainer = Color(0xFFDC2626),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF475569),
    scrim = Color(0x80000000),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF1E293B),
    inversePrimary = Color(0xFF6366F1)
)

@Composable
fun FloatingVolumeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

object GlassTheme {
    // Widget dimensions - more compact
    val collapsedSize = 48.dp
    val expandedWidth = 180.dp
    val cornerRadius = 24.dp
    val elevation = 4.dp

    // Button sizes - smaller for compact design
    val buttonSize = 36.dp
    val iconSize = 18.dp
    val closeButtonSize = 24.dp

    // Glass effect colors
    @Composable
    fun glassBackground() = if (isSystemInDarkTheme()) {
        Color(0x40FFFFFF) // 25% white overlay in dark mode
    } else {
        Color(0x40000000) // 25% black overlay in light mode
    }

    @Composable
    fun glassBorder() = if (isSystemInDarkTheme()) {
        Color(0x30FFFFFF) // Subtle white border in dark mode
    } else {
        Color(0x20000000) // Subtle dark border in light mode
    }

    @Composable
    fun primaryGlass() = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)

    @Composable
    fun secondaryGlass() = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)

    @Composable
    fun surfaceGlass() = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)

    @Composable
    fun errorGlass() = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
}