package com.example.sharedl.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Used on Android 10 and 11, which predate wallpaper-based dynamic color.
private val LightColors = lightColorScheme(
    primary = Color(0xFF00696B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F2),
    onPrimaryContainer = Color(0xFF002020),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D6),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFF9CF1F2),
)

/**
 * Follows the phone's own Material You colors where available. This app mostly lives in the share
 * sheet and notifications, so it should feel like part of the system rather than a separate brand.
 */
@Composable
fun ShareDLTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
