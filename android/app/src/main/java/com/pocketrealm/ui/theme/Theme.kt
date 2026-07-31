package com.pocketrealm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1F6FEB),
    secondary = Color(0xFFF0B429),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    error = Color(0xFFDA3633),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    secondary = Color(0xFFB07D00),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFDA3633),
)

@Composable
fun PocketRealmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
