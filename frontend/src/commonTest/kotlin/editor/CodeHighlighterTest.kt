package editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import dev.snipme.highlights.model.SyntaxLanguage
import editor.CodeHighlighter.Span
import theme.AppTheme
import theme.editorPaletteFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeHighlighterTest {

    private val syntax = editorPaletteFor(AppTheme.LIGHT).syntax

    private fun color(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

    /** The color styles covering [index]; after flattening there must be exactly one. */
    private fun AnnotatedString.colorsAt(index: Int): List<Color> =
        spanStyles.filter { index >= it.start && index < it.end && it.item.color != Color.Unspecified }.map { it.item.color }

    private fun AnnotatedString.isItalicAt(index: Int): Boolean =
        spanStyles.any { index >= it.start && index < it.end && it.item.fontStyle == FontStyle.Italic }

    @Test
    fun `flatten lets a comment win over tokens inside it and keeps tokens after it`() {
        val comment = Color.Gray
        val brace = Span(10, 11, Color.Red)
        val number = Span(20, 21, Color.Magenta)
        val after = Span(40, 43, Color.Blue)

        val flat = CodeHighlighter.flatten(listOf(brace, number, after, Span(5, 30, comment)), setOf(comment))

        assertEquals(listOf(Span(5, 30, comment), after), flat)
    }

    @Test
    fun `everything inside a block comment is drawn as a comment, braces and numbers included`() {
        // The reported case: code inside the braces of a commented-out struct still looked like code,
        // because the comment color was a nested style that the screen lost after the first inner token.
        val code = "class main(){\n    /* testing\n    struct map {\n        int kit =0\n    }\n    */\n}\n"
        val highlighted = CodeHighlighter.highlight(code, SyntaxLanguage.CPP, syntax)

        for (i in code.indices) {
            assertEquals(1, highlighted.colorsAt(i).size, "color styles covering '${code[i]}' at $i")
        }
        for (i in code.indexOf("/*") until code.indexOf("*/") + 2) {
            assertEquals(color(syntax.multilineComment), highlighted.colorsAt(i).single(), "color of '${code[i]}' at $i")
            assertTrue(highlighted.isItalicAt(i), "'${code[i]}' at $i is italic")
        }
    }

    @Test
    fun `code after a block comment is highlighted as code`() {
        val code = "/* note */\nint main() { return 0; }\n"
        val highlighted = CodeHighlighter.highlight(code, SyntaxLanguage.CPP, syntax)

        for (keyword in listOf("int", "return")) {
            val at = code.indexOf(keyword)
            assertEquals(color(syntax.keyword), highlighted.colorsAt(at).single(), "color of '$keyword'")
            assertFalse(highlighted.isItalicAt(at), "'$keyword' is not italic")
        }
    }
}
