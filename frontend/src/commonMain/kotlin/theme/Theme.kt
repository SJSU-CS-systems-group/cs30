package theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

val AccentBlue = Color(0xFF1565C0)
val PassGreen  = Color(0xFF2E7D32)
val FailRed    = Color(0xFFC62828)
val CodeFont   = FontFamily.Monospace

// Shared dimension tokens
object Dims {
    val topBarHeight        = 48.dp
    val toolbarButtonHeight = 32.dp
    val outputPanelHeight   = 240.dp
    val gutterWidth         = 56.dp
    val iconSize            = 24.dp
}

// Shared monospace text style for code editor, output, and inputs
val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize   = 13.sp,
    lineHeight = 20.sp,
)

// CS30 typography — IDE-compact scale
private val CS30Typography = Typography(
    titleMedium  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelLarge   = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium  = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall   = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
    bodyMedium   = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall    = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

// CS30 shape system — compact, IDE-like corners
private val CS30Shapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),   // badges, chips
    small      = RoundedCornerShape(4.dp),   // code blocks, case rows, toolbar buttons
    medium     = RoundedCornerShape(6.dp),   // cards, dialogs, dropdowns
    large      = RoundedCornerShape(8.dp),   // panels, bottom sheets
)

enum class AppTheme {
    LIGHT, DARK,
    LIGHT_COLORBLIND, DARK_COLORBLIND,
    LIGHT_ANSI, DARK_ANSI;

    val displayName: String
        get() = when (this) {
            LIGHT -> "Light mode"
            DARK -> "Dark mode"
            LIGHT_COLORBLIND -> "Light mode (colorblind-friendly)"
            DARK_COLORBLIND -> "Dark mode (colorblind-friendly)"
            LIGHT_ANSI -> "Light mode (ANSI colors only)"
            DARK_ANSI -> "Dark mode (ANSI colors only)"
        }
}

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
    error           = Color(0xFFB00020),
    onError         = Color.White,
)

private val CS30DarkColorScheme = darkColorScheme(
    primary         = Color(0xFF90CAF9),
    onPrimary       = Color.White,
    secondary       = Color(0xFF81D4FA),
    onSecondary     = Color.White,
    background      = Color(0xFF121212),
    onBackground    = Color(0xFFE1E1E1),
    surface         = Color(0xFF1E1E1E),
    onSurface       = Color(0xFFE1E1E1),
    surfaceVariant  = Color(0xFF2A2A2A),
    outline         = Color(0xFF555555),
    error           = Color(0xFFFF5555),
    onError         = Color(0xFF000000),
)

private val CS30LightColorblindScheme = lightColorScheme(
    primary         = Color(0xFF0072B2),
    onPrimary       = Color.White,
    secondary       = Color(0xFFE69F00),
    onSecondary     = Color.White,
    tertiary        = Color(0xFF0072B2),
    background      = Color(0xFFFAFAFA),
    onBackground    = Color(0xFF1C1C1C),
    surface         = Color.White,
    onSurface       = Color(0xFF1C1C1C),
    surfaceVariant  = Color(0xFFF0F0F0),
    outline         = Color(0xFFCCCCCC),
    error           = Color(0xFFE69F00),
)

private val CS30DarkColorblindScheme = darkColorScheme(
    primary         = Color(0xFF56B4E9),
    onPrimary       = Color.White,
    secondary       = Color(0xFFE69F00),
    onSecondary     = Color.White,
    tertiary        = Color(0xFF56B4E9),
    background      = Color(0xFF121212),
    onBackground    = Color(0xFFE1E1E1),
    surface         = Color(0xFF1E1E1E),
    onSurface       = Color(0xFFE1E1E1),
    surfaceVariant  = Color(0xFF2A2A2A),
    outline         = Color(0xFF555555),
    error           = Color(0xFFE69F00),
)

private val CS30LightAnsiScheme = lightColorScheme(
    primary         = Color(0xFF0000AA),
    onPrimary       = Color.White,
    secondary       = Color(0xFF00AA00),
    onSecondary     = Color.White,
    tertiary        = Color(0xFF0000AA),
    background      = Color(0xFFFFFFFF),
    onBackground    = Color(0xFF000000),
    surface         = Color(0xFFFFFFFF),
    onSurface       = Color(0xFF000000),
    surfaceVariant  = Color(0xFFF0F0F0),
    outline         = Color(0xFF808080),
    error           = Color(0xFFAA0000),
)

private val CS30DarkAnsiScheme = darkColorScheme(
    primary         = Color(0xFF5555FF),
    onPrimary       = Color(0xFF000000),
    secondary       = Color(0xFF55FF55),
    onSecondary     = Color(0xFF000000),
    tertiary        = Color(0xFF5555FF),
    background      = Color(0xFF000000),
    onBackground    = Color(0xFFFFFFFF),
    surface         = Color(0xFF111111),
    onSurface       = Color(0xFFFFFFFF),
    surfaceVariant  = Color(0xFF222222),
    outline         = Color(0xFF777777),
    error           = Color(0xFFFF5555),
)

val AppTheme.isDark: Boolean get() = name.startsWith("DARK")

@Composable
fun CS30Theme(theme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    val colorScheme = when (theme) {
        AppTheme.LIGHT -> CS30LightColorScheme
        AppTheme.DARK -> CS30DarkColorScheme
        AppTheme.LIGHT_COLORBLIND -> CS30LightColorblindScheme
        AppTheme.DARK_COLORBLIND -> CS30DarkColorblindScheme
        AppTheme.LIGHT_ANSI -> CS30LightAnsiScheme
        AppTheme.DARK_ANSI -> CS30DarkAnsiScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = CS30Typography,
        shapes      = CS30Shapes,
        content     = content
    )
}
