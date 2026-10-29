package com.arpit.headphonebatterytracker.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondaryContainer = SlateSelectedDark,
    onSecondaryContainer = InkDark,
    background = SlateBgDark,
    onBackground = InkDark,
    surface = SlateBgDark,
    onSurface = InkDark,
    surfaceVariant = SlateRaisedDark,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerLowest = SlateBgDark,
    surfaceContainerLow = SlateSurfaceDark,
    surfaceContainer = SlateSurfaceDark,
    surfaceContainerHigh = SlateRaisedDark,
    surfaceContainerHighest = SlateHighDark,
    outline = InkMutedDark,
    outlineVariant = SlateOutlineDark,
)

private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    secondaryContainer = SlateSelectedLight,
    onSecondaryContainer = InkLight,
    background = SlateBgLight,
    onBackground = InkLight,
    surface = SlateBgLight,
    onSurface = InkLight,
    surfaceVariant = SlateRaisedLight,
    onSurfaceVariant = InkMutedLight,
    surfaceContainerLowest = SlateSurfaceLight,
    surfaceContainerLow = SlateSurfaceLight,
    surfaceContainer = SlateSurfaceLight,
    surfaceContainerHigh = SlateRaisedLight,
    surfaceContainerHighest = SlateHighLight,
    outline = InkMutedLight,
    outlineVariant = SlateOutlineLight,
)

@Composable
fun HeadphoneBatteryTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off: the app uses its own palette so mode colours always read the same, whatever the wallpaper.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
