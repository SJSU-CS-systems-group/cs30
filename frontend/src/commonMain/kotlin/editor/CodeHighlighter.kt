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
 * Three steps: [tokenize] (the only part that knows the library), [flatten] (resolve overlaps), and
 * [render]. Highlights returns spans for keyword/string/literal(number)/comment/metadata/punctuation;
 * plain identifiers keep the default `code` color, and comments are italic as a non-color cue (helps
 * high-contrast / ANSI legibility).
 */
object CodeHighlighter {

    /** A colored range of the text, `start` inclusive, `end` exclusive. */
    internal data class Span(val start: Int, val end: Int, val color: Color) {
        fun overlaps(other: Span): Boolean = start < other.end && other.start < end
    }

    /** Map the problem's free-form language label to a Highlights language. */
    fun languageFor(label: String): SyntaxLanguage = when (label.trim().lowercase()) {
        "java" -> SyntaxLanguage.JAVA
        "python", "py", "python3" -> SyntaxLanguage.PYTHON
        "c++", "cpp", "cxx", "cc" -> SyntaxLanguage.CPP
        "c" -> SyntaxLanguage.C
        else -> SyntaxLanguage.DEFAULT
    }

    fun highlight(code: String, language: SyntaxLanguage, syntax: SyntaxTheme): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        val commentColors = setOf(rgbToColor(syntax.comment), rgbToColor(syntax.multilineComment))
        val spans = flatten(tokenize(code, language, syntax), commentColors)
        return render(code, spans, rgbToColor(syntax.code), commentColors)
    }

    /** The code uncolored, for when syntax highlighting is turned off. */
    fun plain(code: String, syntax: SyntaxTheme): AnnotatedString =
        buildAnnotatedString { withStyle(SpanStyle(color = rgbToColor(syntax.code))) { append(code) } }

    /** The tokenizer's color spans, clamped to the text; empty ones dropped. */
    private fun tokenize(code: String, language: SyntaxLanguage, syntax: SyntaxTheme): List<Span> =
        Highlights.Builder()
            .code(code)
            .language(language)
            .theme(syntax)
            .build()
            .getHighlights()
            .filterIsInstance<ColorHighlight>()
            .mapNotNull { h ->
                val start = h.location.start.coerceIn(0, code.length)
                val end = h.location.end.coerceIn(start, code.length)
                if (start == end) null else Span(start, end, rgbToColor(h.rgb))
            }

    /**
     * Non-overlapping spans, sorted by start. Highlights also returns spans *inside* comments
     * (punctuation, numbers, ...); drawn as nested styles, the comment color was lost on screen after
     * the first one. So comments win over anything inside them, and of two overlapping tokens the one
     * starting first is kept.
     */
    internal fun flatten(spans: List<Span>, commentColors: Set<Color>): List<Span> {
        val (comments, tokens) = spans.partition { it.color in commentColors }
        val candidates = comments + tokens.filter { token -> comments.none { it.overlaps(token) } }
        val flat = mutableListOf<Span>()
        for (span in candidates.sortedBy { it.start }) {
            if (flat.lastOrNull()?.overlaps(span) != true) flat += span
        }
        return flat
    }

    /** One color style per character: gaps in [defaultColor], comments also italic. */
    private fun render(code: String, spans: List<Span>, defaultColor: Color, commentColors: Set<Color>) =
        buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                if (span.start > cursor) withStyle(SpanStyle(color = defaultColor)) { append(code, cursor, span.start) }
                val italic = if (span.color in commentColors) FontStyle.Italic else null
                withStyle(SpanStyle(color = span.color, fontStyle = italic)) { append(code, span.start, span.end) }
                cursor = span.end
            }
            if (cursor < code.length) withStyle(SpanStyle(color = defaultColor)) { append(code, cursor, code.length) }
        }

    /** Highlights stores colors as 0xRRGGBB; add full alpha for an opaque Compose Color. */
    private fun rgbToColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
}
