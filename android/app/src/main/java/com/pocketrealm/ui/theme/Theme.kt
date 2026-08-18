package com.pocketrealm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE9B949),
    onPrimary = Color(0xFF241A00),
    secondary = Color(0xFF8FB8D8),
    tertiary = Color(0xFFF0C674),
    background = Color(0xFF0B1118),
    surface = Color(0xFF121B25),
    surfaceVariant = Color(0xFF1C2A38),
    onSurface = Color(0xFFE7EDF4),
    onSurfaceVariant = Color(0xFFB7C4D1),
    error = Color(0xFFFFB4AB),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF745B00),
    onPrimary = Color.White,
    secondary = Color(0xFF365F7D),
    tertiary = Color(0xFF765B00),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5EBF1),
    onSurface = Color(0xFF17212B),
    onSurfaceVariant = Color(0xFF4D5E6D),
    error = Color(0xFFBA1A1A),
)

private val RealmShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

private val RealmTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun PocketRealmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RealmTypography,
        shapes = RealmShapes,
        content = content,
    )
}
