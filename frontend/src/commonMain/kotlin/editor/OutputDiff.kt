package editor

enum class DiffMark { MATCH, DIFF }

/** Half-open [start, end) range within one output line, to be colored by [mark]. */
data class DiffSpan(val start: Int, val end: Int, val mark: DiffMark)

/**
 * One line's diff. [exact] is true only when the line is identical to its counterpart, and that is
 * what earns it the "correct" color: a line whose characters all appear on the other side but
 * which is still missing some of them is not correct, and must not read as if it were.
 */
data class DiffLine(val spans: List<DiffSpan>, val exact: Boolean)

// Above these the O(n*m) LCS table gets too big to build on every recomposition.
private const val MAX_LCS_LINES = 300
private const val MAX_LCS_CHAR_CELLS = 200_000

/**
 * Diffs [text] against [against] and returns, per line of [text], the spans to color. Lines are
 * aligned with an LCS first, so one extra or missing line doesn't mark everything after it wrong,
 * then each line that has a counterpart is diffed character by character: characters the other
 * side also has are [DiffMark.MATCH], the rest are [DiffMark.DIFF]. Comparison is exact — the row
 * already shows a failing verdict, so a difference the judge cared about is not smoothed over.
 *
 * Run it in both directions to color both columns: over the output it marks what is wrong or
 * extra, over the expected output it marks what is missing.
 */
internal fun diffLines(text: List<String>, against: List<String>): List<DiffLine> {
    val pairing = alignLines(text, against)
    return text.mapIndexed { i, line ->
        val j = pairing[i]
        val exact = j >= 0 && against[j] == line
        val spans = when {
            line.isEmpty() -> emptyList()
            exact -> listOf(DiffSpan(0, line.length, DiffMark.MATCH))
            j < 0 -> listOf(DiffSpan(0, line.length, DiffMark.DIFF))
            else -> charSpans(line, against[j])
        }
        DiffLine(spans, exact)
    }
}

/**
 * A short note for a failed row whose difference has nothing visible to color, because both sides
 * agree on every non-empty line and only blank lines or line breaks differ. Null when there is a
 * real character difference — the colored spans already show that.
 */
internal fun invisibleDiffHint(actual: String, expected: String): String? {
    if (actual == expected) return "differs in characters not shown"
    val actualLines = actual.lines()
    val expectedLines = expected.lines()
    if (actualLines.filter { it.isNotEmpty() } != expectedLines.filter { it.isNotEmpty() }) return null
    return when {
        actualLines.size > expectedLines.size -> "extra blank line"
        actualLines.size < expectedLines.size -> "missing blank line"
        else -> "blank lines differ"
    }
}

/** For each actual line, the expected line it lines up with, or -1 when it has no counterpart. */
private fun alignLines(actual: List<String>, expected: List<String>): IntArray {
    val pairing = IntArray(actual.size) { -1 }
    if (actual.size > MAX_LCS_LINES || expected.size > MAX_LCS_LINES) {
        for (i in actual.indices) if (i < expected.size) pairing[i] = i
        return pairing
    }

    val lcs = lcsTable(actual, expected)
    var i = 0
    var j = 0
    while (i < actual.size) {
        if (j < expected.size && actual[i] == expected[j]) {
            pairing[i] = j
            i++
            j++
            continue
        }
        // Collect one run of unmatched lines from each side and pair them off in order, so a line
        // that was merely changed still gets a counterpart to diff against.
        val added = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        while (i < actual.size && !(j < expected.size && actual[i] == expected[j])) {
            if (j >= expected.size || lcs[i + 1][j] >= lcs[i][j + 1]) {
                added.add(i)
                i++
            } else {
                removed.add(j)
                j++
            }
        }
        // Actual ran out mid-hunk: whatever is left of expected belongs to this hunk too.
        while (i >= actual.size && j < expected.size) {
            removed.add(j)
            j++
        }
        added.forEachIndexed { k, line -> if (k < removed.size) pairing[line] = removed[k] }
    }
    return pairing
}

private fun charSpans(actual: String, expected: String): List<DiffSpan> {
    if (actual.length.toLong() * expected.length > MAX_LCS_CHAR_CELLS) {
        return listOf(DiffSpan(0, actual.length, DiffMark.DIFF))
    }
    return runsOf(lcsMarks(actual.toList(), expected.toList()))
}

/** Marks each element of [a] as present in [b] (in order) or not. */
private fun <T> lcsMarks(a: List<T>, b: List<T>): List<DiffMark> {
    val lcs = lcsTable(a, b)
    val marks = ArrayList<DiffMark>(a.size)
    var i = 0
    var j = 0
    while (i < a.size) {
        when {
            j >= b.size -> { marks.add(DiffMark.DIFF); i++ }
            a[i] == b[j] -> { marks.add(DiffMark.MATCH); i++; j++ }
            // Consuming from a is at least as good: it's an extra or wrong element.
            lcs[i + 1][j] >= lcs[i][j + 1] -> { marks.add(DiffMark.DIFF); i++ }
            // Otherwise the b element is missing from a; skip it, there is nothing to color.
            else -> j++
        }
    }
    return marks
}

/** lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..]. */
private fun <T> lcsTable(a: List<T>, b: List<T>): Array<IntArray> {
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }
    return lcs
}

private fun runsOf(marks: List<DiffMark>): List<DiffSpan> {
    val spans = mutableListOf<DiffSpan>()
    var start = 0
    while (start < marks.size) {
        var end = start + 1
        while (end < marks.size && marks[end] == marks[start]) end++
        spans.add(DiffSpan(start, end, marks[start]))
        start = end
    }
    return spans
}
