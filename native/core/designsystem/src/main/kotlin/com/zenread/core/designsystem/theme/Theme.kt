package com.zenread.core.designsystem.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.zenread.core.model.ThemeAccent

/**
 * App theme: Material 3 Expressive, wallpaper-based dynamic color on Android 12+ (or one of the
 * [ThemeAccent] presets), and an optional pure-black dark mode for OLED screens.
 */
@Composable
fun ZenReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    accent: ThemeAccent = ThemeAccent.TEAL,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = when {
        dynamicColor && supportsDynamicColor() ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> accentColorScheme(accent, darkTheme)
    }
    val colorScheme = if (darkTheme && pureBlack) base.toPureBlack() else base

    CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/** Whether the device has Material You wallpaper colors (Android 12+). */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private val LocalAppDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * Whether the app is drawn dark. This is the user's Settings → Appearance choice, which can differ
 * from the system's dark mode; use it instead of `isSystemInDarkTheme()` inside the app.
 */
@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(): Boolean = LocalAppDarkTheme.current ?: isSystemInDarkTheme()

private fun ColorScheme.toPureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
)
