package editor

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorHistoryTest {

    /** A tiny editor: the text, a clock, and the history recording every change. */
    private class Editor(start: String = "") {
        val history = EditorHistory()
        var text = start
        var nowMs = 0L

        init {
            history.reset(text, TextRange(text.length))
        }

        /** Types [chars] one at a time at the end, [gapMs] apart. */
        fun type(chars: String, gapMs: Long = 100) {
            for (c in chars) {
                nowMs += gapMs
                change(text + c)
            }
        }

        /** Any other change (paste, indent, load...), recorded like the editor's observer does. */
        fun change(newText: String) {
            text = newText
            history.record(text, TextRange(text.length), nowMs)
        }

        fun undo(): Boolean = history.undo(text, TextRange(text.length), nowMs)?.also { text = it.text } != null
        fun redo(): Boolean = history.redo(text, TextRange(text.length), nowMs)?.also { text = it.text } != null
    }

    @Test
    fun `undo then redo restores the text exactly`() {
        val editor = Editor("int x;")
        editor.change("int x;\nint y;")

        editor.undo()
        assertEquals("int x;", editor.text)
        editor.redo()
        assertEquals("int x;\nint y;", editor.text)
    }

    @Test
    fun `a burst of typing is one undo step and a pause starts a new one`() {
        val editor = Editor()
        editor.type("abc")
        editor.nowMs += EditorHistory.TYPING_MERGE_MS
        editor.type("def")

        editor.undo()
        assertEquals("abc", editor.text)
        editor.undo()
        assertEquals("", editor.text)
    }

    @Test
    fun `a multi-character change is its own step`() {
        val editor = Editor()
        editor.type("ab")
        editor.change(editor.text + "PASTED")
        editor.type("cd")

        editor.undo()
        assertEquals("abPASTED", editor.text)
        editor.undo()
        assertEquals("ab", editor.text)
    }

    @Test
    fun `undo after paste then typing only undoes the typing, and redo brings it back`() {
        // The reported bug: the old paste stack jumped straight back to the pre-paste text and
        // wiped redo, losing everything typed since the paste.
        val editor = Editor("start\n")
        editor.change(editor.text + "pasted();\n")
        editor.nowMs += EditorHistory.TYPING_MERGE_MS
        editor.type("typed")

        editor.undo()
        assertEquals("start\npasted();\n", editor.text)
        editor.redo()
        assertEquals("start\npasted();\ntyped", editor.text)
    }

    @Test
    fun `a programmatic deletion like backspacing an auto-pair is undoable`() {
        val editor = Editor("f()")
        editor.change("f")       // handleBackspacePair removes both brackets
        editor.type("oo")

        editor.undo()
        assertEquals("f", editor.text)
        editor.undo()
        assertEquals("f()", editor.text)
    }

    @Test
    fun `a new change after undo clears redo`() {
        val editor = Editor()
        editor.change("one")
        editor.undo()
        editor.change("two")

        assertNull(editor.history.redo(editor.text, TextRange(0), editor.nowMs))
        assertEquals("two", editor.text)
    }

    @Test
    fun `undo records a change it has not seen yet before undoing`() {
        val editor = Editor("a")
        editor.change("ab")
        editor.nowMs += EditorHistory.TYPING_MERGE_MS   // a separate step, not part of the same burst
        editor.text = "abc"      // changed, but the observer hasn't recorded it yet

        editor.undo()
        assertEquals("ab", editor.text)
    }

    @Test
    fun `history keeps at most the step cap`() {
        val editor = Editor()
        repeat(EditorHistory.MAX_UNDO_STEPS + 5) { editor.change(editor.text + "xx") }

        var undone = 0
        while (editor.undo()) undone++
        assertEquals(EditorHistory.MAX_UNDO_STEPS, undone)
    }

    @Test
    fun `diff finds the single replaced region`() {
        val edit = EditorHistory.diff("int x = 1;", "int y = 1;", TextRange(4), TextRange(5))
        assertEquals(4, edit.start)
        assertEquals("x", edit.removed)
        assertEquals("y", edit.inserted)
    }
}
