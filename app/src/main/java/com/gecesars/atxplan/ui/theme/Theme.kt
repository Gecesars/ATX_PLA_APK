package com.gecesars.atxplan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = AtxTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F1EC),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = AtxNavySoft,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = AtxNavy,
    tertiary = AtxAmber,
    onTertiary = Color(0xFF3F2E00),
    background = AtxBackground,
    onBackground = AtxNavy,
    surface = AtxSurface,
    onSurface = AtxNavy,
    surfaceVariant = Color(0xFFE5EDF2),
    onSurfaceVariant = Color(0xFF3E5362),
    outline = AtxOutline,
    error = AtxDanger,
)

private val DarkColorScheme = darkColorScheme(
    primary = AtxTealLight,
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF9CF2E9),
    secondary = Color(0xFFA8CCE8),
    onSecondary = AtxNavy,
    secondaryContainer = AtxNavySoft,
    onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFFFFD166),
    onTertiary = Color(0xFF3F2E00),
    background = AtxDarkBackground,
    onBackground = Color(0xFFE5EDF2),
    surface = AtxDarkSurface,
    onSurface = Color(0xFFE5EDF2),
    surfaceVariant = AtxDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC1D1DC),
    outline = Color(0xFF8FA5B3),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AtxPlanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AtxTypography,
        content = content,
    )
}
