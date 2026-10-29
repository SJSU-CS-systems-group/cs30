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

/** [indent] one level shallower: one trailing tab, or up to [INDENT_SIZE] trailing spaces, removed. */
internal fun outdented(indent: String): String =
    if (indent.endsWith('\t')) indent.dropLast(1)
    else indent.dropLast(minOf(INDENT_SIZE, indent.length - indent.trimEnd(' ').length))

/** Start offsets of every line that the [min, max] range touches, ascending. A selection ending
 *  right after a newline (whole lines dragged over) doesn't touch the line after it. */
private fun lineStartsInRange(text: CharSequence, min: Int, max: Int): List<Int> {
    // Collected ascending (the first line start ≤ min, subsequent ones increase), so dedup is enough.
    val starts = mutableListOf(lineStartOffset(text, min))
    var i = min
    while (i < max - 1) {
        if (text[i] == '\n') starts.add(i + 1)
        i++
    }
    return starts.distinct()
}

/** Shift+Tab: remove one indent level — a leading tab, or up to 4 leading spaces — from the caret
 *  line / each selected line. A programmatic edit{}; undoable through the editor's EditorHistory. */
fun TextFieldState.handleShiftTab() = edit {
    val text = asCharSequence().toString()
    val sel = selection
    val starts = lineStartsInRange(text, sel.min, sel.max)
    var removedFirst = 0
    var removedTotal = 0
    for (ls in starts.asReversed()) {
        var n = 0
        if (ls < text.length && text[ls] == '\t') n = 1
        else while (n < INDENT_SIZE && ls + n < text.length && text[ls + n] == ' ') n++
        if (n > 0) {
            replace(ls, ls + n, "")
            removedTotal += n
            if (ls == starts.first()) removedFirst = n
        }
    }
    val newStart = (sel.min - removedFirst).coerceAtLeast(0)
    selection = TextRange(newStart, (sel.max - removedTotal).coerceAtLeast(newStart))
}

/** Tab with a selection: indent each selected line one level (instead of replacing the selection),
 *  keeping the lines selected. A programmatic edit{}; undoable through the editor's EditorHistory. */
fun TextFieldState.handleTabIndent() = edit {
    val text = asCharSequence().toString()
    val sel = selection
    val starts = lineStartsInRange(text, sel.min, sel.max)
    val indent = " ".repeat(INDENT_SIZE)
    for (ls in starts.asReversed()) replace(ls, ls, indent)
    val newStart = if (sel.min == starts.first()) sel.min else sel.min + INDENT_SIZE
    selection = TextRange(newStart, sel.max + INDENT_SIZE * starts.size)
}

/** Backspace between an empty auto-pair like `(|)` deletes both. Returns true if it handled it.
 *  A programmatic edit{} (it removes characters); undoable through the editor's EditorHistory. */
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
            '}' -> if (original.length == 0 && inserted.length == 1) outdentCloser(inserted)
        }
    }

    /** A `}` typed alone on its line lines up with its `{`: the indent before it loses one level. */
    private fun TextFieldBuffer.outdentCloser(inserted: TextRange) {
        val text = asCharSequence()
        val lineStart = lineStartOffset(text, inserted.min)
        val indent = text.substring(lineStart, inserted.min)
        val restOfLineIsBlank = inserted.max == length || text[inserted.max] == '\n'
        if (indent.isEmpty() || indent.isNotBlank() || !restOfLineIsBlank) return
        val newIndent = outdented(indent)
        replace(lineStart, inserted.min, newIndent)
        selection = TextRange(lineStart + newIndent.length + 1)
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
 * [selected] wrapped in the pair [typed] opens — `"text"`, `(text)` — or null when [typed] isn't an
 * opener, so typing an opener over a selection wraps it instead of replacing it (as in VS Code).
 */
internal fun wrapInPair(selected: CharSequence, typed: Char): String? =
    PAIRS[typed]?.let { closer -> "$typed$selected$closer" }

/** Characters before which an opener is auto-closed (besides end of text and whitespace), as in VS Code. */
private val AUTO_CLOSE_BEFORE = setOf(')', ']', '}', ',', ';')

/**
 * Whether typing [typed] (between [before] and [after]; null = text edge) should auto-insert its
 * closer: only in front of nothing, whitespace or a closer — so `(` typed before `x + 1` stays `(` —
 * and never a quote right after a letter or digit, so `don't` gets no stray quote.
 */
internal fun shouldAutoClose(before: Char?, typed: Char, after: Char?): Boolean {
    val closer = PAIRS[typed] ?: return false
    val beforeOk = closer != typed || before == null || !before.isLetterOrDigit()
    val afterOk = after == null || after.isWhitespace() || after in AUTO_CLOSE_BEFORE
    return beforeOk && afterOk
}

/**
 * Offsets of the closers the editor auto-inserted, so typing a closer only steps over one of those —
 * never over a `)` the student typed and may need another of. Offsets follow later edits via [map].
 */
internal class AutoClosedPairs {
    private val offsets = mutableSetOf<Int>()

    fun track(offset: Int) {
        offsets += offset
    }

    /** True (and forgets it) if an auto-inserted closer sits at [offset]. */
    fun consume(offset: Int): Boolean = offsets.remove(offset)

    /** Follows an edit that replaced [original] with [newLength] characters: later offsets shift, ones inside go. */
    fun map(original: TextRange, newLength: Int) {
        val delta = newLength - original.length
        val mapped = offsets.mapNotNull { offset ->
            when {
                offset < original.min -> offset
                offset >= original.max -> offset + delta
                else -> null
            }
        }
        offsets.clear()
        offsets += mapped
    }

    fun clear() = offsets.clear()
}

/**
 * Auto-close brackets/quotes, wrap a selection when an opener is typed over it, and step over a
 * closer the editor inserted when that closer is typed (so `(` then `)` gives `()`, not `())`).
 * One instance per editor: it remembers which closers it inserted.
 */
class AutoPairTransformation : InputTransformation {
    private val autoClosed = AutoClosedPairs()
    private var lastText: String? = null

    override fun TextFieldBuffer.transformInput() {
        // Edits that don't come through typing (web paste, Shift+Tab, undo...) can't be followed,
        // so forget the tracked closers rather than risk stepping over the wrong one.
        if (lastText?.contentEquals(originalText) != true) autoClosed.clear()
        for (i in (0 until changes.changeCount).sortedByDescending { changes.getOriginalRange(it).min }) {
            autoClosed.map(changes.getOriginalRange(i), changes.getRange(i).length)
        }
        pairOrWrap()
        lastText = asCharSequence().toString()
    }

    private fun TextFieldBuffer.pairOrWrap() {
        if (changes.changeCount != 1) return
        val inserted = changes.getRange(0)
        val original = changes.getOriginalRange(0)
        if (inserted.length != 1) return // single-character typing only
        val text = asCharSequence()
        val c = text[inserted.min]
        if (original.length != 0) {
            // Typed over a selection: wrap it, keeping the wrapped text selected
            val wrapped = wrapInPair(originalText.subSequence(original.min, original.max), c) ?: return
            replace(inserted.min, inserted.max, wrapped)
            selection = TextRange(inserted.min + 1, inserted.min + wrapped.length - 1)
            return
        }
        // Step over a closer this editor inserted.
        if (c in CLOSERS && inserted.max < length && text[inserted.max] == c && autoClosed.consume(inserted.max)) {
            replace(inserted.min, inserted.max, "")
            selection = TextRange(inserted.min + 1)
            return
        }
        val before = if (inserted.min > 0) text[inserted.min - 1] else null
        val after = if (inserted.max < length) text[inserted.max] else null
        if (!shouldAutoClose(before, c, after)) return
        replace(inserted.max, inserted.max, PAIRS.getValue(c).toString())
        autoClosed.track(inserted.max)
        selection = TextRange(inserted.max) // caret between opener and inserted closer
    }
}

/**
 * Combined transformation: Enter/Tab indentation + auto-pair brackets. One instance per editor.
 */
class FullEditorTransformation : InputTransformation {
    private val autoPair = AutoPairTransformation()

    override fun TextFieldBuffer.transformInput() {
        with(CodeInputTransformation) { transformInput() }
        with(autoPair) { transformInput() }
    }
}
