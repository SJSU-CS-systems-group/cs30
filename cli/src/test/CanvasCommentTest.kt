import com.cs30.cli.Submissions2Canvas
import com.cs30.server.service.BestSubmission
import com.cs30.server.service.CanvasSubmission
import com.cs30.server.service.CanvasSubmissionComment
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the comment body and the marker that decides whether a submission was already mirrored.
 * Getting the marker wrong either re-posts on every run or never posts at all, and neither shows up
 * as a failure at the Canvas API level.
 */
class CanvasCommentTest {

    private val command = Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true))

    private fun submission(
        passed: Int = 7,
        total: Int = 10,
        code: String = "print(1)",
        at: String = "2026-07-27T21-39-23",
        fileName: String = "submission-2026-07-27T21-39-23.py",
    ) = BestSubmission(passed, total, fileName, code, at)

    private fun withComments(vararg texts: String) =
        CanvasSubmission(
            id = 1,
            userId = 5,
            submissionComments = texts.map { CanvasSubmissionComment(comment = it) },
        )

    @Test
    fun `no comments means nothing was mirrored yet`() {
        assertNull(command.lastSyncedTimestamp(CanvasSubmission(id = 1, userId = 5)))
        assertNull(command.lastSyncedTimestamp(withComments()))
    }

    @Test
    fun `unrelated comments are ignored`() {
        val existing = withComments("Nice work!", "see me after class")
        assertNull(command.lastSyncedTimestamp(existing))
    }

    @Test
    fun `the newest mirrored timestamp wins`() {
        val existing = withComments(
            "[cs30-sync 2026-07-20T10-00-00] Best submission for p: 3/10 test cases passed (30%).",
            "a human comment",
            "[cs30-sync 2026-07-27T21-39-23] Best submission for p: 7/10 test cases passed (70%).",
        )
        assertEquals("2026-07-27T21-39-23", command.lastSyncedTimestamp(existing))
    }

    @Test
    fun `a mirrored marker round-trips out of the comment this tool writes`() {
        val text = command.commentFor("babyshark", submission(at = "2026-07-27T21-39-23"))
        val parsed = command.lastSyncedTimestamp(withComments(text))
        assertEquals(
            "2026-07-27T21-39-23", parsed,
            "the marker written into a comment must be readable back, or re-runs post duplicates",
        )
    }

    @Test
    fun `comment states the score and inlines escaped source`() {
        val text = command.commentFor("babyshark", submission(passed = 7, total = 10, code = "if (a<b) {}"))
        assertTrue(text.contains("7/10"), text)
        assertTrue(text.contains("70%"), text)
        assertTrue(text.contains("babyshark"), text)
        assertTrue(text.contains("<pre>"), text)
        assertTrue(text.contains("if (a&lt;b) {}"), "source must be HTML escaped: $text")
        assertFalse(text.contains("if (a<b)"), "raw unescaped source must not appear")
    }

    @Test
    fun `oversized source is omitted rather than inlined`() {
        val big = "x".repeat(9 * 1024)
        val text = command.commentFor("babyshark", submission(code = big))
        assertFalse(text.contains("<pre>"), "source over the limit must not be inlined")
        assertTrue(text.contains("Source omitted"), text)
        assertTrue(text.contains("7/10"), "the score is still reported: $text")
    }

    @Test
    fun `a zero-testcase submission does not divide by zero`() {
        val text = command.commentFor("babyshark", submission(passed = 0, total = 0))
        assertTrue(text.contains("0/0"), text)
        assertTrue(text.contains("0%"), text)
    }
}
