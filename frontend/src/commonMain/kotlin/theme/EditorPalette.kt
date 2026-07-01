package theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Shared, theme-aware editor palette. The same *semantic token model* is used by every theme; only
 * the colors differ. [syntax] feeds the Highlights tokenizer (keyword / string / literal-number /
 * comment / multilineComment / metadata-annotation / punctuation-operator / code-default). The
 * remaining fields cover editor chrome and the output panel so accessibility (selection, current
 * line, line numbers, focus, console, verdicts) is consistent and readable per theme.
 *
 * Note: Highlights does not distinguish Type vs Function vs Variable — those share the `code`
 * (default) color by design (like default Monaco/CodeMirror). Errors/Warnings are not produced by
 * the lexer (no diagnostics engine); [fail]/[warning] are used by the output panel instead.
 */
data class EditorPalette(
    val syntax: SyntaxTheme,
    val selection: Color,
    val currentLine: Color,
    val lineNumber: Color,
    val indentGuide: Color,
    val focus: Color,
    val consoleForeground: Color,
    val pass: Color,
    val fail: Color,
    val warning: Color,
)

val LocalEditorPalette = staticCompositionLocalOf<EditorPalette> {
    error("LocalEditorPalette not provided. Wrap content in CS30Theme { }.")
}

fun editorPaletteFor(theme: AppTheme): EditorPalette = when (theme) {
    AppTheme.LIGHT               -> LightEditorPalette
    AppTheme.DARK                -> DarkEditorPalette
    AppTheme.LIGHT_HIGH_CONTRAST -> LightHighContrastEditorPalette
    AppTheme.DARK_HIGH_CONTRAST  -> DarkHighContrastEditorPalette
    AppTheme.LIGHT_ANSI          -> LightAnsiEditorPalette
    AppTheme.DARK_ANSI           -> DarkAnsiEditorPalette
}

// 0xRRGGBB (Highlights) -> opaque Compose Color.
private fun codeColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

// --- Light & Dark delegate their token colors to a recognized standard scheme (IntelliJ/Darcula,
// bundled with Highlights) instead of hand-picked hexes; only the chrome extras below are ours.
// High Contrast and ANSI can't use a stock aesthetic palette (WCAG/CVD/16-color constraints), so
// their SyntaxTheme stays custom. Highlights SyntaxTheme colors are 0xRRGGBB Ints. ---

private val LightSyntaxTheme = SyntaxThemes.darcula(darkMode = false)
private val DarkSyntaxTheme = SyntaxThemes.darcula(darkMode = true)

private val LightEditorPalette = EditorPalette(
    syntax = LightSyntaxTheme,
    selection = Color(0x66ADD6FF), currentLine = Color(0xFFF0F4FF), lineNumber = Color(0xFF9AA0A6),
    indentGuide = Color(0xFFE0E0E0),
    focus = Color(0xFF1565C0), consoleForeground = codeColor(LightSyntaxTheme.code),
    pass = Color(0xFF2E7D32), fail = Color(0xFFC62828), warning = Color(0xFF9A5700),
)

private val DarkEditorPalette = EditorPalette(
    syntax = DarkSyntaxTheme,
    selection = Color(0x66264F78), currentLine = Color(0xFF2A2D2E), lineNumber = Color(0xFF858585),
    indentGuide = Color(0xFF404040),
    focus = Color(0xFF90CAF9), consoleForeground = codeColor(DarkSyntaxTheme.code),
    pass = Color(0xFF4EC97E), fail = Color(0xFFF48771), warning = Color(0xFFCCA700),
)

// High contrast: blue/magenta/teal/amber/brown families with large lightness spread (no red-vs-green
// lexical pairing); every lexical token targets ~5:1+ on its pure white/black background.
private val LightHighContrastEditorPalette = EditorPalette(
    syntax = SyntaxTheme(
        key = "cs30-light-hc",
        code = 0x000000, keyword = 0x0000CC, string = 0xA21BA2, literal = 0x0E6E6E,
        comment = 0x595959, metadata = 0x7A3E00, multilineComment = 0x595959,
        punctuation = 0x222222, mark = 0x000000,
    ),
    selection = Color(0x803D7EFF), currentLine = Color(0xFFEAF0FF), lineNumber = Color(0xFF3A3A3A),
    indentGuide = Color(0xFFCCCCCC),
    focus = Color(0xFF0000CC), consoleForeground = Color(0xFF000000),
    pass = Color(0xFF1B5E20), fail = Color(0xFFB30000), warning = Color(0xFF8A5A00),
)

private val DarkHighContrastEditorPalette = EditorPalette(
    syntax = SyntaxTheme(
        key = "cs30-dark-hc",
        code = 0xFFFFFF, keyword = 0x5AB0FF, string = 0xFF8AD8, literal = 0xB8C7FF,
        comment = 0xA0A0A0, metadata = 0xFFC857, multilineComment = 0xA0A0A0,
        punctuation = 0xE0E0E0, mark = 0xFFFFFF,
    ),
    selection = Color(0x801F4D7A), currentLine = Color(0xFF15181C), lineNumber = Color(0xFFB0B0B0),
    indentGuide = Color(0xFF555555),
    focus = Color(0xFF5AB0FF), consoleForeground = Color(0xFFFFFFFF),
    pass = Color(0xFF5EE38A), fail = Color(0xFFFF6B6B), warning = Color(0xFFFFD23F),
)

// ANSI: only the 16 standard ANSI colors for syntax/accents. Neutral chrome bands (current line,
// selection) use black/white with alpha so no non-ANSI hue is introduced.
private val LightAnsiEditorPalette = EditorPalette(
    syntax = SyntaxTheme(
        key = "cs30-light-ansi",
        code = 0x000000, keyword = 0x0000AA, string = 0x00AA00, literal = 0xAA00AA,
        comment = 0x555555, metadata = 0xAA5500, multilineComment = 0x555555,
        punctuation = 0x000000, mark = 0x000000,
    ),
    selection = Color(0x550000AA), currentLine = Color(0x14000000), lineNumber = Color(0xFF555555),
    indentGuide = Color(0xFFAAAAAA),
    focus = Color(0xFF0000AA), consoleForeground = Color(0xFF000000),
    pass = Color(0xFF00AA00), fail = Color(0xFFAA0000), warning = Color(0xFFAA5500),
)

private val DarkAnsiEditorPalette = EditorPalette(
    syntax = SyntaxTheme(
        key = "cs30-dark-ansi",
        code = 0xAAAAAA, keyword = 0x5555FF, string = 0x55FF55, literal = 0xFF55FF,
        comment = 0x555555, metadata = 0xFFFF55, multilineComment = 0x555555,
        punctuation = 0xAAAAAA, mark = 0xAAAAAA,
    ),
    selection = Color(0x555555FF), currentLine = Color(0x18FFFFFF), lineNumber = Color(0xFFAAAAAA),
    indentGuide = Color(0xFF555555),
    focus = Color(0xFF5555FF), consoleForeground = Color(0xFFAAAAAA),
    pass = Color(0xFF55FF55), fail = Color(0xFFFF5555), warning = Color(0xFFFFFF55),
)
