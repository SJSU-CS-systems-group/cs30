package editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndentGuidesTest {

    @Test
    fun `guide levels follow VS Code, including blank lines, partial indents and tabs`() {
        val code = listOf(
            "int main() {",
            "    if (x) {",
            "        a();",
            "",                 // blank inside the inner block: its guides continue through it
            "        b();",
            "    }",
            "",                 // blank before a dedent: inside the block that ends below
            "  c();",           // a partial indent still gets a guide
            "\td();",           // a tab is one level
            "}",
        )
        assertEquals(listOf(0, 1, 2, 2, 2, 1, 1, 1, 1, 0), indentGuideLevels(code, offSide = false))

        // Python (offSide): the blank line before a dedent takes the level of the code below
        val python = listOf("def f():", "    if x:", "        a()", "", "    b()")
        assertEquals(listOf(0, 1, 2, 1, 1), indentGuideLevels(python, offSide = true))
        assertEquals(listOf(0, 1, 2, 2, 1), indentGuideLevels(python, offSide = false))
    }

    @Test
    fun `the active guide is the block holding the cursor, or the block a line opens`() {
        val levels = listOf(0, 1, 2, 2, 2, 1, 0)   // int main() { / if { / a / blank / b / } / }
        assertEquals(ActiveIndentGuide(2, 4, 2), activeIndentGuide(levels, cursorLine = 3))
        assertEquals(ActiveIndentGuide(2, 4, 2), activeIndentGuide(levels, cursorLine = 1))   // on `if (x) {`
        assertEquals(ActiveIndentGuide(1, 3, 1), activeIndentGuide(listOf(0, 1, 1, 1, 0), cursorLine = 2))
        assertNull(activeIndentGuide(listOf(0, 0), cursorLine = 0))
    }
}
