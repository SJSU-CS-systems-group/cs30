package editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class LineCommentTest {

    @Test
    fun `toggling comments the selected lines at their smallest indent, and toggling again restores them`() {
        val code = "    a();\n        b();\nc();"
        for (token in listOf("//", "#")) {
            val state = TextFieldState(code, TextRange(0, 21))   // the first two lines selected

            state.toggleLineComment(token)
            assertEquals("    $token a();\n    $token     b();\nc();", state.text.toString())

            state.toggleLineComment(token)
            assertEquals(code, state.text.toString())
        }
    }
}
