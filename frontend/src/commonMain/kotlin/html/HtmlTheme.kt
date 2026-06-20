package html

import androidx.compose.ui.graphics.Color

/**
 * Theme colors (as CSS hex strings) injected into the rendered problem-statement HTML so the
 * WebView (desktop) / iframe (web) match the active app theme instead of defaulting to white/black.
 * Built from `MaterialTheme.colorScheme` in [editor.ProblemPanel] and threaded down to the renderer.
 */
data class HtmlTheme(
    val background: String,
    val foreground: String,
    val codeBackground: String,
    val codeForeground: String,
    val border: String,
    val link: String,
) {
    companion object {
        val DEFAULT = HtmlTheme(
            background = "#FFFFFF", foreground = "#1C1C1C",
            codeBackground = "#F0F0F0", codeForeground = "#1C1C1C",
            border = "#CCCCCC", link = "#1565C0",
        )
    }
}

private fun Int.hex2(): String = (this and 0xFF).toString(16).padStart(2, '0')

/** Compose [Color] -> "#RRGGBB". `String.format` isn't available on wasm, so build it manually. */
fun Color.toCssHex(): String {
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return "#${r.hex2()}${g.hex2()}${b.hex2()}"
}
