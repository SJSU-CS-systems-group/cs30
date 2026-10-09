package ta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestCaseHtmlTest {

    @Test
    fun `escape keeps printable ASCII and tabs and escapes HTML specials`() {
        assertEquals("a\tb &lt;tag&gt; &amp; &quot;q&quot;", TestCaseHtml.escape("a\tb <tag> & \"q\""))
    }

    @Test
    fun `escape writes non-ASCII as numeric entities so the HTML normalizer can't alter it`() {
        // Armenian, Cyrillic, Greek, Coptic, as in easyascab2's secret/zz_100_1000
        assertEquals("&#x548;&#x449;&#x3a9;&#x3e2;", TestCaseHtml.escape("ՈщΩϢ"))
    }

    @Test
    fun `escape writes a surrogate pair as one code point and keeps control characters`() {
        assertEquals("&#x1f600;", TestCaseHtml.escape("😀"))
        assertEquals("a&#xd;b", TestCaseHtml.escape("a\rb"))
    }

    @Test
    fun `firstLines counts lines without a trailing newline line and handles CRLF`() {
        assertEquals(listOf("1", "2", "3") to 3, TestCaseHtml.firstLines("1\n2\n3\n"))
        assertEquals(listOf("1", "2") to 2, TestCaseHtml.firstLines("1\r\n2\r\n"))
        assertEquals(emptyList<String>() to 0, TestCaseHtml.firstLines(""))
    }

    @Test
    fun `firstLines caps the shown lines but reports the full count`() {
        val total = TestCaseHtml.MAX_DISPLAYED_LINES + 5
        val text = (1..total).joinToString("\n")

        val (shown, count) = TestCaseHtml.firstLines(text)

        assertEquals(TestCaseHtml.MAX_DISPLAYED_LINES, shown.size)
        assertEquals(total, count)
        assertEquals(TestCaseHtml.MAX_DISPLAYED_LINES.toString(), shown.last())
    }

    @Test
    fun `body labels both columns with line counts and only notes the cap when it applies`() {
        val small = TestCaseHtml.body("1 2\n3 4\n", "7\n")
        assertTrue("Input · 2 lines" in small)
        assertTrue("Expected output · 1 lines" in small)
        assertFalse("Showing first" in small)

        val big = TestCaseHtml.body((1..TestCaseHtml.MAX_DISPLAYED_LINES + 1).joinToString("\n"), "")
        assertTrue("Showing first ${TestCaseHtml.MAX_DISPLAYED_LINES} of ${TestCaseHtml.MAX_DISPLAYED_LINES + 1} lines" in big)
    }

    @Test
    fun `body gives each column its own wrap toggle and one numbered row per line`() {
        val html = TestCaseHtml.body("a\nb\nc\n", "x\n")

        assertTrue("""id="wrap-input"""" in html && """for="wrap-input"""" in html)
        assertTrue("""id="wrap-expected"""" in html && """for="wrap-expected"""" in html)
        assertEquals(4, Regex("""class="tc-line"""").findAll(html).count())
        assertTrue("""<span class="tc-num">3</span><span class="tc-text">c</span>""" in html)
    }
}
