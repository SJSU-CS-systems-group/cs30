package editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import dev.snipme.highlights.model.SyntaxLanguage

/** The line-comment token for [language]: `#` for Python, `//` for the other languages the editor supports. */
fun lineCommentToken(language: SyntaxLanguage): String = if (language == SyntaxLanguage.PYTHON) "#" else "//"

/**
 * Ctrl/Cmd+/ as in VS Code: comments out the caret line or the selected lines with [token] — or, if every
 * non-blank one is already commented, uncomments them. The token goes at the smallest indentation among the
 * lines so the block stays aligned; blank lines are left alone, and the selection keeps covering the same text.
 * A programmatic edit{}; undoable through the editor's EditorHistory.
 */
fun TextFieldState.toggleLineComment(token: String) = edit {
    val text = asCharSequence().toString()
    val sel = selection
    val lines = lineStartsInRange(text, sel.min, sel.max)
        .map { start -> start until (text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length) }
        .filter { text.substring(it).isNotBlank() }
    if (lines.isEmpty()) return@edit

    val indents = lines.map { line -> text.substring(line).takeWhile { it == ' ' || it == '\t' }.length }
    val uncomment = lines.indices.all { i -> text.startsWith(token, lines[i].first + indents[i]) }
    val minIndent = indents.min()
    var start = sel.start
    var end = sel.end

    // Bottom line first, so the offsets of the lines above stay valid
    for (i in lines.indices.reversed()) {
        if (uncomment) {
            val at = lines[i].first + indents[i]
            val length = token.length + if (text.startsWith("$token ", at)) 1 else 0
            replace(at, at + length, "")
            start = shiftAfterRemoval(start, at, length)
            end = shiftAfterRemoval(end, at, length)
        } else {
            val at = lines[i].first + minIndent
            replace(at, at, "$token ")
            if (at <= start) start += token.length + 1
            if (at <= end) end += token.length + 1
        }
    }
    selection = TextRange(start, end)
}

/** [offset] after [length] characters at [at] were removed. */
private fun shiftAfterRemoval(offset: Int, at: Int, length: Int): Int = when {
    offset >= at + length -> offset - length
    offset > at -> at
    else -> offset
}
