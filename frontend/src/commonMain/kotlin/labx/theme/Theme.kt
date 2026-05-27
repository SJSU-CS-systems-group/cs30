package labx.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val AccentBlue = Color(0xFF1565C0)
val PassGreen  = Color(0xFF2E7D32)
val FailRed    = Color(0xFFC62828)
val CodeFont   = FontFamily.Monospace

private val CS30ColorScheme = lightColorScheme(
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

@Composable
fun CS30Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CS30ColorScheme,
        content = content
    )
}
