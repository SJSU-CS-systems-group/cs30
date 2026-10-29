package editor

/*
 * Indent guides as VS Code computes them - a direct port of `computeIndentLevel` (model/utils.ts) and
 * `getLinesIndentGuides` / `_getIndentLevelForWhitespaceLine` / `getActiveIndentGuide`
 * (model/guidesTextModelPart.ts). A line with level n gets guides at columns 0, 4, … 4(n-1).
 */

/** Width of [line]'s indentation in columns (a tab runs to the next tab stop), or -1 if the line is blank. */
internal fun indentWidth(line: String): Int {
    var width = 0
    for (c in line) {
        width = when (c) {
            ' ' -> width + 1
            '\t' -> width - width % INDENT_SIZE + INDENT_SIZE
            else -> return width
        }
    }
    return -1
}

/**
 * The number of indent guides on each of [lines]. A line with code gets one per started indent level; a
 * blank line takes its guides from the nearest code lines above and below, so guides run unbroken through
 * blank lines inside a block. [offSide] is for indentation-scoped languages (Python): a blank line before
 * a dedent takes the level of the code below it.
 */
fun indentGuideLevels(lines: List<String>, offSide: Boolean): List<Int> {
    val widths = lines.map(::indentWidth)
    val above = IntArray(lines.size)   // width of the nearest code line above each line, -1 if none
    val below = IntArray(lines.size)   // ... and below
    var last = -1
    for (i in lines.indices) { above[i] = last; if (widths[i] >= 0) last = widths[i] }
    last = -1
    for (i in lines.indices.reversed()) { below[i] = last; if (widths[i] >= 0) last = widths[i] }

    return lines.indices.map { i ->
        when {
            widths[i] >= 0 -> ceilLevels(widths[i])
            above[i] == -1 || below[i] == -1 -> 0                               // top or bottom of the file
            above[i] < below[i] -> 1 + above[i] / INDENT_SIZE                   // inside the block above
            above[i] == below[i] -> ceilLevels(below[i])                        // between two blocks
            offSide -> ceilLevels(below[i])                                     // same level as the code below
            else -> 1 + below[i] / INDENT_SIZE                                  // inside the block that ends below
        }
    }
}

private fun ceilLevels(width: Int): Int = (width + INDENT_SIZE - 1) / INDENT_SIZE

/** The highlighted guide: guide number [level] on lines [firstLine]..[lastLine] (0-based). */
data class ActiveIndentGuide(val firstLine: Int, val lastLine: Int, val level: Int)

/**
 * The guide of the block holding [cursorLine], highlighted like VS Code's active indent guide, or null at
 * the top level. On a block's opening (or closing) line it is the guide of the block it opens (or closes).
 */
fun activeIndentGuide(levels: List<Int>, cursorLine: Int): ActiveIndentGuide? {
    val cursorLevel = levels[cursorLine]
    var first = cursorLine
    var last = cursorLine
    var level = cursorLevel
    var extendUp = true
    var extendDown = true
    when (cursorLevel + 1) {
        levels.getOrNull(cursorLine + 1) -> { first = cursorLine + 1; last = first; level++; extendUp = false }
        levels.getOrNull(cursorLine - 1) -> { first = cursorLine - 1; last = first; level++; extendDown = false }
    }
    if (level == 0) return null
    if (extendUp) while (first > 0 && levels[first - 1] >= level) first--
    if (extendDown) while (last < levels.lastIndex && levels[last + 1] >= level) last++
    return ActiveIndentGuide(first, last, level)
}
