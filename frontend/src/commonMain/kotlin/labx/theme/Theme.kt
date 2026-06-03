package labx.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val AccentBlue = Color(0xFF1565C0)
val PassGreen  = Color(0xFF2E7D32)
val FailRed    = Color(0xFFC62828)
val CodeFont   = FontFamily.Monospace

private val CS30LightColorScheme = lightColorScheme(
    primary         = AccentBlue,
    onPrimary       = Color.White,
    secondary       = Color(0xFF0277BD),
    onSecondary     = Color.White,
    background      = Color(0xFFFAFAFA),
    onBackground    = Color(0xFF1C1C1C),
    surface         = Color.White,
    onSurface       = Color(0xFF1C1C1C),
    surfaceVariant  = Color(0xFFF0F0F0),
    outline         = Color(0xFFCCCCCC),
)

private val CS30DarkColorScheme = darkColorScheme(
    primary         = Color(0xFF90CAF9),
    onPrimary       = Color(0xFF003258),
    secondary       = Color(0xFF81D4FA),
    onSecondary     = Color(0xFF004455),
    background      = Color(0xFF121212),
    onBackground    = Color(0xFFE1E1E1),
    surface         = Color(0xFF1E1E1E),
    onSurface       = Color(0xFFE1E1E1),
    surfaceVariant  = Color(0xFF2A2A2A),
    outline         = Color(0xFF555555),
)

@Composable
fun CS30Theme(isDark: Boolean = false, content: @Composable () -> Unit) {
    val colorScheme = if (isDark) CS30DarkColorScheme else CS30LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
