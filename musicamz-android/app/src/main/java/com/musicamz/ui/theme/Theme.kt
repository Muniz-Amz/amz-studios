package com.musicamz.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AmzAccent,
    onPrimary = AmzBlack,
    primaryContainer = AmzAccentSoft,
    onPrimaryContainer = Color(0xFFE9E1FF),
    secondary = AmzPulse,
    onSecondary = AmzBlack,
    secondaryContainer = Color(0xFF10372F),
    onSecondaryContainer = Color(0xFFD4FFF5),
    tertiary = Color(0xFFFF9DBA),
    onTertiary = AmzBlack,
    tertiaryContainer = Color(0xFF4A1D31),
    onTertiaryContainer = Color(0xFFFFD9E4),
    background = AmzBlack,
    onBackground = AmzText,
    surface = AmzDark,
    onSurface = AmzText,
    surfaceVariant = AmzCard,
    onSurfaceVariant = AmzMuted,
    outline = AmzBorder,
    outlineVariant = AmzBorder.copy(alpha = 0.65f),
    inverseSurface = AmzText,
    inverseOnSurface = AmzBlack,
    inversePrimary = AmzAccent,
    error = Color(0xFFFF879D),
    errorContainer = Color(0xFF4B1E2A),
    onError = AmzBlack,
    onErrorContainer = Color(0xFFFFD9E0)
)

private val LightColorScheme = lightColorScheme(
    primary = AmzAccent,
    onPrimary = AmzBlack,
    secondary = AmzDark,
    background = Color.White,
    onBackground = AmzBlack,
    surface = Color.White,
    onSurface = AmzBlack
)

@Composable
fun MusicAmzTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = if (darkTheme) AmzBlack.toArgb() else colorScheme.primary.toArgb()
            window.navigationBarColor = if (darkTheme) AmzBlack.toArgb() else colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
