package theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import cs30.frontend.generated.resources.JetBrainsMono_Regular
import cs30.frontend.generated.resources.Res
import org.jetbrains.compose.resources.Font

val AccentBlue = Color(0xFF1565C0)
val PassGreen  = Color(0xFF2E7D32)
val FailRed    = Color(0xFFC62828)

val LocalCodeFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

val CodeFont: FontFamily
    @Composable get() = LocalCodeFont.current

// Shared dimension tokens
object Dims {
    val topBarHeight        = 48.dp
    val toolbarButtonHeight = 32.dp
    val outputPanelHeight = 240.dp
    val gutterWidth         = 56.dp
    val iconSize            = 24.dp
}

// Shared monospace text style for code editor, output, and inputs
val MonoTextStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = CodeFont,
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
    LIGHT_HIGH_CONTRAST, DARK_HIGH_CONTRAST,
    LIGHT_ANSI, DARK_ANSI;

    val displayName: String
        get() = when (this) {
            LIGHT -> "Light"
            DARK -> "Dark"
            LIGHT_HIGH_CONTRAST -> "Light (High Contrast)"
            DARK_HIGH_CONTRAST -> "Dark (High Contrast)"
            LIGHT_ANSI -> "Light (ANSI)"
            DARK_ANSI -> "Dark (ANSI)"
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

// High-contrast schemes: pure white/black surfaces and near-max foreground contrast so editor
// text and chrome clear WCAG AA. Accent hues use blue/amber families (no red-vs-green reliance).
private val CS30LightHighContrastScheme = lightColorScheme(
    primary         = Color(0xFF0000CC),
    onPrimary       = Color.White,
    secondary       = Color(0xFF7A3E00),
    onSecondary     = Color.White,
    tertiary        = Color(0xFF0000CC),
    background      = Color(0xFFFFFFFF),
    onBackground    = Color(0xFF000000),
    surface         = Color(0xFFFFFFFF),
    onSurface       = Color(0xFF000000),
    surfaceVariant  = Color(0xFFEAEAEA),
    onSurfaceVariant= Color(0xFF1A1A1A),
    outline         = Color(0xFF000000),
    error           = Color(0xFFB30000),
    onError         = Color.White,
)

private val CS30DarkHighContrastScheme = darkColorScheme(
    primary         = Color(0xFF5AB0FF),
    onPrimary       = Color(0xFF000000),
    secondary       = Color(0xFFFFC857),
    onSecondary     = Color(0xFF000000),
    tertiary        = Color(0xFF5AB0FF),
    background      = Color(0xFF000000),
    onBackground    = Color(0xFFFFFFFF),
    surface         = Color(0xFF000000),
    onSurface       = Color(0xFFFFFFFF),
    surfaceVariant  = Color(0xFF1A1A1A),
    onSurfaceVariant= Color(0xFFE6E6E6),
    outline         = Color(0xFFE0E0E0),
    error           = Color(0xFFFF6B6B),
    onError         = Color(0xFF000000),
)

// ANSI schemes use ONLY the standard 16-color ANSI palette. Surfaces are pure black/white;
// panel separation comes from the outline/divider (bright-black / white) rather than tints.
private val CS30LightAnsiScheme = lightColorScheme(
    primary         = Color(0xFF0000AA), // blue
    onPrimary       = Color(0xFFFFFFFF),
    secondary       = Color(0xFF00AA00), // green
    onSecondary     = Color(0xFF000000),
    tertiary        = Color(0xFF0000AA),
    background      = Color(0xFFFFFFFF),
    onBackground    = Color(0xFF000000),
    surface         = Color(0xFFFFFFFF),
    onSurface       = Color(0xFF000000),
    surfaceVariant  = Color(0xFFFFFFFF),
    onSurfaceVariant= Color(0xFF000000),
    outline         = Color(0xFF555555), // bright-black
    error           = Color(0xFFAA0000), // red
    onError         = Color(0xFFFFFFFF),
)

private val CS30DarkAnsiScheme = darkColorScheme(
    primary         = Color(0xFF5555FF), // bright-blue
    onPrimary       = Color(0xFF000000),
    secondary       = Color(0xFF55FF55), // bright-green
    onSecondary     = Color(0xFF000000),
    tertiary        = Color(0xFF5555FF),
    background      = Color(0xFF000000),
    onBackground    = Color(0xFFFFFFFF),
    surface         = Color(0xFF000000),
    onSurface       = Color(0xFFFFFFFF),
    surfaceVariant  = Color(0xFF000000),
    onSurfaceVariant= Color(0xFFFFFFFF),
    outline         = Color(0xFFAAAAAA), // white
    error           = Color(0xFFFF5555), // bright-red
    onError         = Color(0xFF000000),
)

val AppTheme.isDark: Boolean get() = name.startsWith("DARK")

@Composable
fun CS30Theme(theme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    val colorScheme = when (theme) {
        AppTheme.LIGHT -> CS30LightColorScheme
        AppTheme.DARK -> CS30DarkColorScheme
        AppTheme.LIGHT_HIGH_CONTRAST -> CS30LightHighContrastScheme
        AppTheme.DARK_HIGH_CONTRAST -> CS30DarkHighContrastScheme
        AppTheme.LIGHT_ANSI -> CS30LightAnsiScheme
        AppTheme.DARK_ANSI -> CS30DarkAnsiScheme
    }
    val palette = editorPaletteFor(theme)
    val codeFont = FontFamily(Font(Res.font.JetBrainsMono_Regular))
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = CS30Typography,
        shapes      = CS30Shapes,
    ) {
        CompositionLocalProvider(
            LocalCodeFont provides codeFont,
            LocalEditorPalette provides palette,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = palette.focus,
                backgroundColor = palette.selection,
            ),
            content = content,
        )
    }
}
