package editor

import androidx.compose.ui.text.TextRange

/**
 * The code editor's undo/redo history.
 *
 * Compose's own history can't be used: every programmatic `TextFieldState.edit { }` that changes the
 * text clears it (`commitEdit` -> `clearHistory`), and the editor has to edit programmatically for
 * paste on wasm, Shift+Tab and auto-pair deletion. So the editor keeps this one history instead, built
 * like CodeMirror's or VS Code's: each change is stored as an [Edit] (what was removed and inserted
 * where), not as a copy of the whole text, and a burst of typing is one undo step.
 *
 * Changes are recorded by comparing the text with the last text the history saw, so how the text
 * changed doesn't matter - typing, paste, indentation, loading a submission - and an edit path added
 * later is covered without being wired in. [undo] and [redo] record any change not yet seen first, so
 * an undo pressed before the latest change was observed still undoes it.
 */
class EditorHistory(
    private val maxSteps: Int = MAX_UNDO_STEPS,
    private val mergeWindowMs: Long = TYPING_MERGE_MS,
) {
    /** One change: [removed] replaced by [inserted] at [start]. */
    data class Edit(
        val start: Int,
        val removed: String,
        val inserted: String,
        val selectionBefore: TextRange,
        val selectionAfter: TextRange,
    )

    /** What undo/redo turns the editor into. */
    data class Restored(val text: String, val selection: TextRange)

    /** One undo step: one edit, or a burst of single-character edits typed together. */
    private class Step(val edits: MutableList<Edit>, var lastAtMs: Long, var canMerge: Boolean)

    private val undoSteps = ArrayDeque<Step>()
    private val redoSteps = ArrayDeque<Step>()
    private var lastText = ""
    private var lastSelection = TextRange.Zero

    /** Starts over from [text], with nothing to undo - e.g. once the saved code has loaded. */
    fun reset(text: String, selection: TextRange) {
        undoSteps.clear()
        redoSteps.clear()
        lastText = text
        lastSelection = selection
    }

    /** Records the editor's current state; a no-op unless the text changed since the last call. */
    fun record(text: String, selection: TextRange, nowMs: Long) {
        if (text == lastText) {
            lastSelection = selection
            return
        }
        val edit = diff(lastText, text, lastSelection, selection)
        val isSingleChar = edit.removed.length + edit.inserted.length == 1
        val top = undoSteps.lastOrNull()
        if (isSingleChar && top != null && top.canMerge && nowMs - top.lastAtMs < mergeWindowMs &&
            continues(top.edits.last(), edit)
        ) {
            top.edits += edit
            top.lastAtMs = nowMs
        } else {
            undoSteps.addLast(Step(mutableListOf(edit), nowMs, canMerge = isSingleChar))
            if (undoSteps.size > maxSteps) undoSteps.removeFirst()
        }
        redoSteps.clear()
        lastText = text
        lastSelection = selection
    }

    /** Undoes the last step of [text]; null if there is nothing to undo. */
    fun undo(text: String, selection: TextRange, nowMs: Long): Restored? {
        record(text, selection, nowMs)
        val step = undoSteps.removeLastOrNull() ?: return null
        var restored = text
        for (edit in step.edits.asReversed()) {
            restored = restored.replaceRange(edit.start, edit.start + edit.inserted.length, edit.removed)
        }
        step.canMerge = false
        redoSteps.addLast(step)
        return settle(restored, step.edits.first().selectionBefore)
    }

    /** Redoes the last undone step of [text]; null if there is nothing to redo (or it was edited since). */
    fun redo(text: String, selection: TextRange, nowMs: Long): Restored? {
        record(text, selection, nowMs)
        val step = redoSteps.removeLastOrNull() ?: return null
        var restored = text
        for (edit in step.edits) {
            restored = restored.replaceRange(edit.start, edit.start + edit.removed.length, edit.inserted)
        }
        undoSteps.addLast(step)
        return settle(restored, step.edits.last().selectionAfter)
    }

    private fun settle(text: String, selection: TextRange): Restored {
        val clamped = TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
        lastText = text
        lastSelection = clamped
        return Restored(text, clamped)
    }

    /** Typing forward or backspacing continues from where the previous edit left off. */
    private fun continues(previous: Edit, next: Edit): Boolean =
        next.start == previous.start + previous.inserted.length || next.start + next.removed.length == previous.start

    companion object {
        /** Edits typed within this long of each other undo together. */
        const val TYPING_MERGE_MS = 1_000L
        const val MAX_UNDO_STEPS = 200

        /** The single replacement turning [old] into [new]: everything between their common start and end. */
        internal fun diff(old: String, new: String, selectionBefore: TextRange, selectionAfter: TextRange): Edit {
            val shorter = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < shorter && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            return Edit(
                start = prefix,
                removed = old.substring(prefix, old.length - suffix),
                inserted = new.substring(prefix, new.length - suffix),
                selectionBefore = selectionBefore,
                selectionAfter = selectionAfter,
            )
        }
    }
}
