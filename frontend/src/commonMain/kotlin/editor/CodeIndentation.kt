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

/** Tab: indent to the next 4-column stop at the caret, or indent every selected line. */
fun TextFieldState.handleTab() = edit {
    val text = asCharSequence().toString()
    val sel = selection
    if (sel.collapsed) {
        val col = sel.start - lineStartOffset(text, sel.start)
        val n = INDENT_SIZE - (col % INDENT_SIZE)
        replace(sel.start, sel.start, " ".repeat(n))
        selection = TextRange(sel.start + n)
    } else {
        val starts = lineStartsInRange(text, sel.min, sel.max)
        for (ls in starts.asReversed()) replace(ls, ls, " ".repeat(INDENT_SIZE))
        selection = TextRange(sel.min + INDENT_SIZE, sel.max + starts.size * INDENT_SIZE)
    }
}

/** Shift+Tab: remove up to 4 leading spaces from the caret line / each selected line. */
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

/** Enter: keep the current line's indent; open an indented block when inside / after a brace. */
fun TextFieldState.handleEnter() = edit {
    val text = asCharSequence().toString()
    val sel = selection
    val indent = leadingWhitespace(text, lineStartOffset(text, sel.start))
    val before = if (sel.start > 0) text[sel.start - 1] else ' '
    val after = if (sel.start < text.length) text[sel.start] else ' '
    when {
        before == '{' && after == '}' -> {
            val inner = indent + " ".repeat(INDENT_SIZE)
            replace(sel.min, sel.max, "\n$inner\n$indent")
            selection = TextRange(sel.min + 1 + inner.length)
        }
        before == '{' -> {
            val inner = indent + " ".repeat(INDENT_SIZE)
            replace(sel.min, sel.max, "\n$inner")
            selection = TextRange(sel.min + 1 + inner.length)
        }
        else -> {
            replace(sel.min, sel.max, "\n$indent")
            selection = TextRange(sel.min + 1 + indent.length)
        }
    }
}

/** Backspace between an empty auto-pair like `(|)` deletes both. Returns true if it handled it. */
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
