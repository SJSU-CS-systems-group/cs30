package editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
