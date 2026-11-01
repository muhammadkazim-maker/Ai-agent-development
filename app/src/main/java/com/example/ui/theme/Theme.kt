package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF021019),
    primaryContainer = Color(0xFF072A3E),
    onPrimaryContainer = Color(0xFFB8F4FF),
    secondary = VioletSecondary,
    onSecondary = Color(0xFF140B2E),
    secondaryContainer = Color(0xFF281952),
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = EmeraldTertiary,
    onTertiary = Color(0xFF002918),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkSurfaceBorder,
    error = DangerRed,
    onError = Color(0xFF3B0510)
)

private val LightColorScheme = lightColorScheme(
    primary = CyanPrimaryVariant,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF4FC),
    onPrimaryContainer = Color(0xFF022029),
    secondary = Color(0xFF6D28D9),
    onSecondary = Color.White,
    tertiary = Color(0xFF059669),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0B1120),
    surface = Color.White,
    onSurface = Color(0xFF0B1120),
    surfaceVariant = Color(0xFFEAF0F8),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFE11D48),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
