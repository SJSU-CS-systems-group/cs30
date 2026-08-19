package editor

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputDiffTest {

    private fun diff(text: String, against: String): List<DiffLine> =
        diffLines(text.split("\n"), against.split("\n"))

    /**
     * Renders one line as <exact line>, (matching text in a line that still differs) and
     * [differing text], so an expectation reads the way the row is colored.
     */
    private fun render(line: String, diffLine: DiffLine): String =
        diffLine.spans.joinToString("") { span ->
            val text = line.substring(span.start, span.end)
            when {
                span.mark == DiffMark.DIFF -> "[$text]"
                diffLine.exact -> "<$text>"
                else -> "($text)"
            }
        }

    private fun renderDiff(text: String, against: String): List<String> {
        val lines = text.split("\n")
        return diff(text, against).mapIndexed { i, diffLine -> render(lines[i], diffLine) }
    }

    @Test
    fun identicalOutputIsAllExact() {
        assertEquals(listOf("<1>", "<2>"), renderDiff("1\n2", "1\n2"))
    }

    @Test
    fun onlyTheWrongCharactersAreMarked() {
        assertEquals(listOf("(ab)[X](d)"), renderDiff("abXd", "abcd"))
    }

    @Test
    fun extraCharactersAreMarkedAndTheRestStaysNeutral() {
        assertEquals(listOf("(hello)[!]"), renderDiff("hello!", "hello"))
    }

    /** The whole point: every character is present in the expected output, yet the line is wrong. */
    @Test
    fun aSubsequenceOfTheExpectedLineIsNeverColoredAsCorrect() {
        assertEquals(listOf("(helo)"), renderDiff("helo", "helloo"))
        assertEquals(listOf("(hel)[l](o)[o]"), renderDiff("helloo", "helo"))
    }

    @Test
    fun onlyTheWrongLineIsDiffed() {
        assertEquals(listOf("<1>", "[9]", "<3>"), renderDiff("1\n9\n3", "1\n2\n3"))
    }

    @Test
    fun extraLineDoesNotShiftTheRestOutOfAlignment() {
        assertEquals(listOf("[0]", "<1>", "<2>"), renderDiff("0\n1\n2", "1\n2"))
    }

    @Test
    fun missingLineLeavesTheRemainingLinesExact() {
        assertEquals(listOf("<1>", "<3>"), renderDiff("1\n3", "1\n2\n3"))
    }

    @Test
    fun linesBeyondExpectedAreFullyMarked() {
        assertEquals(listOf("<1>", "[2]"), renderDiff("1\n2", "1"))
    }

    @Test
    fun trailingSpaceIsMarked() {
        assertEquals(listOf("(1)[ ]"), renderDiff("1 ", "1"))
    }

    @Test
    fun emptyLineHasNoSpans() {
        assertEquals(emptyList(), diff("", "1").first().spans)
    }

    @Test
    fun blankLineOnlyDifferenceIsReportedAsAHint() {
        assertEquals("extra blank line", invisibleDiffHint("1\n\n2", "1\n2"))
        assertEquals("missing blank line", invisibleDiffHint("1\n2", "1\n\n2"))
        assertEquals("blank lines differ", invisibleDiffHint("\n1\n2", "1\n2\n"))
    }

    @Test
    fun noHintWhenCharactersActuallyDiffer() {
        assertEquals(null, invisibleDiffHint("1\n9", "1\n2"))
    }

    @Test
    fun longOutputFallsBackToPositionalLineAlignment() {
        val expected = (1..400).joinToString("\n")
        val actual = (1..400).joinToString("\n") { if (it == 7) "x" else "$it" }
        val diffed = diff(actual, expected)
        assertEquals(400, diffed.size)
        assertEquals(listOf(DiffSpan(0, 1, DiffMark.DIFF)), diffed[6].spans)
        assertEquals(399, diffed.count { it.exact })
    }
}
