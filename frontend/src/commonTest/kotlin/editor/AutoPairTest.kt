package editor

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoPairTest {

    @Test
    fun `typing an opener over a selection wraps it instead of replacing it`() {
        // Students select code and type " or ( expecting VS Code's wrap; it used to wipe the selection
        assertEquals("\"hello\"", wrapInPair("hello", '"'))
        assertEquals("(a + b)", wrapInPair("a + b", '('))
        assertEquals("{\n    x;\n}", wrapInPair("\n    x;\n", '{'))
    }

    @Test
    fun `any other character typed over a selection still replaces it`() {
        assertNull(wrapInPair("hello", 'x'))
        assertNull(wrapInPair("hello", ')'))   // a closer is not an opener
    }

    @Test
    fun `an opener auto-closes only in front of nothing, whitespace or a closer`() {
        assertFalse(shouldAutoClose(before = ' ', typed = '(', after = 'x'))  // ( before "x + 1" stays (
        assertTrue(shouldAutoClose(before = 'f', typed = '(', after = null))
        assertTrue(shouldAutoClose(before = 'f', typed = '(', after = '\n'))
        assertTrue(shouldAutoClose(before = '(', typed = '(', after = ')'))
    }

    @Test
    fun `a quote right after a letter or digit is not auto-closed`() {
        assertFalse(shouldAutoClose(before = 'n', typed = '\'', after = ' '))  // don't
        assertTrue(shouldAutoClose(before = ' ', typed = '"', after = null))
        assertTrue(shouldAutoClose(before = '(', typed = '"', after = ')'))
    }

    @Test
    fun `only a closer the editor inserted is stepped over, and it follows later edits`() {
        val pairs = AutoClosedPairs()
        pairs.track(5)

        assertFalse(pairs.consume(9))               // a closer the student typed is not stepped over
        pairs.map(TextRange(0, 0), newLength = 2)   // typing 2 chars before it moves it to 7
        assertTrue(pairs.consume(7))
        assertFalse(pairs.consume(7))               // stepped over once only

        pairs.track(3)
        pairs.map(TextRange(2, 4), newLength = 1)   // an edit over it forgets it
        assertFalse(pairs.consume(3))
        assertFalse(pairs.consume(2))
    }
}
