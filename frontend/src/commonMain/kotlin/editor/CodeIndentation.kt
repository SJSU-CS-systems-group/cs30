@file:OptIn(ExperimentalFoundationApi::class)

package editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** Soft-tab width: Tab inserts spaces to the next multiple of this. */
const val INDENT_SIZE = 4

/** Openers → matching closer (quotes pair to themselves). */
private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
private val CLOSERS = PAIRS.values.toSet()

private fun lineStartOffset(text: CharSequence, pos: Int): Int {
    var i = pos - 1
    while (i >= 0 && text[i] != '\n') i--
    return i + 1
}

private fun leadingWhitespace(text: CharSequence, lineStart: Int): String {
    val sb = StringBuilder()
    var i = lineStart
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) { sb.append(text[i]); i++ }
    return sb.toString()
}

/** Start offsets of every line that the [min, max] range touches, ascending. */
private fun lineStartsInRange(text: CharSequence, min: Int, max: Int): List<Int> {
    // Collected ascending (the first line start ≤ min, subsequent ones increase), so dedup is enough.
    val starts = mutableListOf(lineStartOffset(text, min))
    var i = min
    while (i < max) {
        if (text[i] == '\n') starts.add(i + 1)
        i++
    }
    return starts.distinct()
}

/** Shift+Tab: remove up to 4 leading spaces from the caret line / each selected line.
 *  Note: This still uses edit{} because it REMOVES characters, which InputTransformation can't do.
 *  Undo won't work for Shift+Tab, but that's acceptable. */
fun TextFieldState.handleShiftTab() = edit {
    val text = asCharSequence().toString()
    val sel = selection
    val starts = lineStartsInRange(text, sel.min, sel.max)
    var removedFirst = 0
    var removedTotal = 0
    for (ls in starts.asReversed()) {
        var n = 0
        while (n < INDENT_SIZE && ls + n < text.length && text[ls + n] == ' ') n++
        if (n > 0) {
            replace(ls, ls + n, "")
            removedTotal += n
            if (ls == starts.first()) removedFirst = n
        }
    }
    val newStart = (sel.min - removedFirst).coerceAtLeast(0)
    selection = TextRange(newStart, (sel.max - removedTotal).coerceAtLeast(newStart))
}

/** Backspace between an empty auto-pair like `(|)` deletes both. Returns true if it handled it.
 *  Note: This still uses edit{} because it REMOVES characters, which InputTransformation can't do. */
fun TextFieldState.handleBackspacePair(): Boolean {
    val sel = selection
    if (!sel.collapsed || sel.start == 0 || sel.start >= text.length) return false
    val closer = PAIRS[text[sel.start - 1]] ?: return false
    if (text[sel.start] != closer) return false
    edit {
        replace(sel.start - 1, sel.start + 1, "")
        selection = TextRange(sel.start - 1)
    }
    return true
}

/**
 * Handles Enter and Tab keys with proper undo/redo support.
 * Uses InputTransformation so edits go through BasicTextField's normal path.
 */
object CodeInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val inserted = changes.getRange(0)
        val original = changes.getOriginalRange(0)
        if (inserted.length < 1) return

        val text = asCharSequence()
        val c = text[inserted.min]

        when (c) {
            '\n' -> handleNewline(inserted, original)
            '\t' -> handleTab(inserted)
        }
    }

    private fun TextFieldBuffer.handleNewline(inserted: TextRange, original: TextRange) {
        // Check if this is a plain newline insertion (original was empty or selection replaced)
        if (original.length != 0 && inserted.length != 1) return

        val text = asCharSequence()

        // Find indentation of the line BEFORE the newline was inserted
        val lineStart = lineStartOffset(text, inserted.min)
        val indent = leadingWhitespaceAt(text, lineStart, inserted.min)

        // Check characters before and after the newline insertion point
        val before = if (inserted.min > 0) text[inserted.min - 1] else ' '
        val after = if (inserted.max < length) text[inserted.max] else ' '

        when {
            before == '{' && after == '}' -> {
                // Between braces: add inner indent line + closing brace line
                val inner = indent + " ".repeat(INDENT_SIZE)
                replace(inserted.min + 1, inserted.max, "$inner\n$indent")
                selection = TextRange(inserted.min + 1 + inner.length)
            }
            before == '{' -> {
                // After opening brace: add extra indent
                val inner = indent + " ".repeat(INDENT_SIZE)
                replace(inserted.min + 1, inserted.max, inner)
                selection = TextRange(inserted.min + 1 + inner.length)
            }
            else -> {
                // Normal case: just add the same indent as previous line
                if (indent.isNotEmpty()) {
                    replace(inserted.min + 1, inserted.max, indent)
                    selection = TextRange(inserted.min + 1 + indent.length)
                }
            }
        }
    }

    private fun TextFieldBuffer.handleTab(inserted: TextRange) {
        val text = asCharSequence()
        // Calculate column position (before the tab was inserted)
        val lineStart = lineStartOffset(text, inserted.min)
        val col = inserted.min - lineStart
        // Replace tab with spaces to next 4-column stop
        val spacesNeeded = INDENT_SIZE - (col % INDENT_SIZE)
        replace(inserted.min, inserted.max, " ".repeat(spacesNeeded))
        selection = TextRange(inserted.min + spacesNeeded)
    }

    private fun leadingWhitespaceAt(text: CharSequence, lineStart: Int, endPos: Int): String {
        val sb = StringBuilder()
        var i = lineStart
        while (i < endPos && (text[i] == ' ' || text[i] == '\t')) { sb.append(text[i]); i++ }
        return sb.toString()
    }

    private fun lineStartOffset(text: CharSequence, pos: Int): Int {
        var i = pos - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }
}

/**
 * Auto-close brackets/quotes, and skip over a closer typed directly in front of the same
 * closer (so `()` + typing `)` gives `()` with the caret moved past, not `())`).
 */
object AutoPairTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val inserted = changes.getRange(0)
        val original = changes.getOriginalRange(0)
        if (original.length != 0 || inserted.length != 1) return // single-char insertions only
        val text = asCharSequence()
        val c = text[inserted.min]
        // Skip over an existing identical closer.
        if (c in CLOSERS && inserted.max < length && text[inserted.max] == c) {
            replace(inserted.min, inserted.max, "")
            selection = TextRange(inserted.min + 1)
            return
        }
        val closer = PAIRS[c] ?: return
        replace(inserted.max, inserted.max, closer.toString())
        selection = TextRange(inserted.max) // caret between opener and inserted closer
    }
}

/**
 * Combined transformation: Enter/Tab indentation + auto-pair brackets.
 */
object FullEditorTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        with(CodeInputTransformation) { transformInput() }
        with(AutoPairTransformation) { transformInput() }
    }
}
