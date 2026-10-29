package editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndentationTest {

    @Test
    fun `Tab over selected lines indents them instead of replacing them`() {
        // Used to replace the selected code with spaces
        val state = TextFieldState("a();\nb();\nc();", TextRange(0, 9))   // "a();\nb();" selected

        state.handleTabIndent()

        assertEquals("    a();\n    b();\nc();", state.text.toString())
        assertEquals(TextRange(0, 17), state.selection)
    }

    @Test
    fun `Shift+Tab removes a leading tab as one indent level`() {
        val state = TextFieldState("\tx = 1", TextRange(3))

        state.handleShiftTab()

        assertEquals("x = 1", state.text.toString())
    }

    @Test
    fun `a closing brace is outdented one level`() {
        assertEquals("    ", outdented("        "))
        assertEquals("", outdented("  "))
        assertEquals("", outdented("\t"))
    }

    @Test
    fun `Backspace in leading spaces goes back to the previous tab stop`() {
        val eight = TextFieldState("        x", TextRange(8))
        assertTrue(eight.handleBackspaceIndent())
        assertEquals("    x", eight.text.toString())

        val six = TextFieldState("      x", TextRange(6))
        six.handleBackspaceIndent()
        assertEquals("    x", six.text.toString())

        val afterCode = TextFieldState("    x", TextRange(5))   // caret after code: a normal delete
        assertFalse(afterCode.handleBackspaceIndent())
        assertEquals("    x", afterCode.text.toString())
    }
}
