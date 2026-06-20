package editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

/**
 * Turns raw editor text into a colored [AnnotatedString] using the Highlights tokenizer (the lexing
 * for Java/Python/C++) and the active theme's [SyntaxTheme] colors. Pure, UI-free, and synchronous —
 * call it inside `remember(text, language, syntax)` so re-tokenization happens only on real changes.
 *
 * Highlights returns spans for keyword/string/literal(number)/comment/metadata/punctuation; plain
 * identifiers (incl. types/functions/variables) keep the default `code` color. We render comments in
 * italic as a non-color redundancy cue (helps high-contrast / ANSI legibility).
 */
object CodeHighlighter {

    /** Map the problem's free-form language label to a Highlights language. */
    fun languageFor(label: String): SyntaxLanguage = when (label.trim().lowercase()) {
        "java" -> SyntaxLanguage.JAVA
        "python", "py", "python3" -> SyntaxLanguage.PYTHON
        "c++", "cpp", "cxx", "cc" -> SyntaxLanguage.CPP
        "c" -> SyntaxLanguage.C
        else -> SyntaxLanguage.DEFAULT
    }

    fun highlight(code: String, language: SyntaxLanguage, syntax: SyntaxTheme): AnnotatedString {
        val defaultColor = rgbToColor(syntax.code)
        if (code.isEmpty()) return AnnotatedString("")

        val highlights = Highlights.Builder()
            .code(code)
            .language(language)
            .theme(syntax)
            .build()
            .getHighlights()

        // Collect color spans by character range, clamped to the text bounds.
        val spans = highlights.filterIsInstance<ColorHighlight>().mapNotNull { h ->
            val start = h.location.start.coerceIn(0, code.length)
            val end = h.location.end.coerceIn(start, code.length)
            if (start == end) null else Triple(start, end, rgbToColor(h.rgb))
        }

        return buildAnnotatedString {
            withStyle(SpanStyle(color = defaultColor)) { append(code) }
            spans.forEach { (start, end, color) ->
                addStyle(SpanStyle(color = color), start, end)
            }
            // Italicize comment ranges for a non-color cue. Comment color is reused for
            // multilineComment in our palettes, so match on either.
            val commentColor = rgbToColor(syntax.comment)
            val multilineColor = rgbToColor(syntax.multilineComment)
            spans.forEach { (start, end, color) ->
                if (color == commentColor || color == multilineColor) {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                }
            }
        }
    }

    /** Highlights stores colors as 0xRRGGBB; add full alpha for an opaque Compose Color. */
    private fun rgbToColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
}
