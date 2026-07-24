package xyz.chenmilin.ankimcpbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFF43A047),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    error = Color(0xFFD32F2F),
)

@Composable
fun AnkiMCPBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
