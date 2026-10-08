package ta

/**
 * Builds the HTML for a test case's input and expected output, shown through the HTML bridge
 * (`ProblemPanel`) rather than drawn on the Compose canvas: the canvas has no OS font fallback, so
 * test data in scripts the app's fonts don't cover (Armenian, Coptic, ...) would render as boxes.
 *
 * `HtmlDocument.build` normalizes the body (Unicode NFC, smart punctuation, control characters),
 * which would alter test data. Every character outside printable ASCII is therefore written as a
 * numeric entity: plain ASCII the normalizer leaves alone, decoded by the browser to the exact
 * original code point.
 */
object TestCaseHtml {

    /** More lines than this are not shown — a browser asked to lay out a million lines stalls. */
    const val MAX_DISPLAYED_LINES = 50_000

    private const val FIRST_PRINTABLE_ASCII = 0x20
    private const val LAST_PRINTABLE_ASCII = 0x7E

    /** Passed as the panel's css; overrides the problem-statement padding/scrolling of the bridge. */
    const val CSS = """
        .problem-container { padding: 0 !important; overflow: hidden !important; height: 100%; }
        .tc-columns { display: flex; gap: 16px; height: 100%; padding: 8px; box-sizing: border-box; }
        .tc-column { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; }
        .tc-label { font: 600 13px system-ui, sans-serif; margin: 0 0 4px; }
        .tc-notice { font: 12px system-ui, sans-serif; opacity: 0.7; margin: 0 0 4px; }
        .tc-scroll { flex: 1; overflow: auto; border-radius: 6px; }
        .tc-lines { display: flex; width: max-content; min-width: 100%; min-height: 100%; }
        .tc-lines pre {
            margin: 0; padding: 8px; white-space: pre;
            font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        }
        .tc-gutter {
            position: sticky; left: 0; text-align: right; user-select: none;
            border-right: 1px solid rgba(127, 127, 127, 0.45);
            background-image: linear-gradient(rgba(127, 127, 127, 0.14), rgba(127, 127, 127, 0.14));
        }
        .tc-gutter span { opacity: 0.6; }
        .tc-data { flex: 1; }
    """

    fun body(input: String, expected: String): String =
        """<div class="tc-columns">${column("Input", input)}${column("Expected output", expected)}</div>"""

    private fun column(label: String, text: String): String {
        val (shownLines, totalLines) = firstLines(text)
        val notice = if (totalLines > shownLines.size) {
            """<p class="tc-notice">Showing first ${shownLines.size} of $totalLines lines</p>"""
        } else ""
        val numbers = (1..shownLines.size).joinToString("\n")
        val data = shownLines.joinToString("\n") { escape(it) }
        return """<div class="tc-column"><p class="tc-label">$label · $totalLines lines</p>$notice""" +
            """<div class="tc-scroll"><div class="tc-lines">""" +
            """<pre class="tc-gutter"><span>$numbers</span></pre><pre class="tc-data">$data</pre>""" +
            """</div></div></div>"""
    }

    /**
     * Up to [MAX_DISPLAYED_LINES] lines, plus the file's total line count. Counts instead of
     * splitting the whole text, so a multi-megabyte test doesn't become a million strings.
     */
    internal fun firstLines(text: String): Pair<List<String>, Int> {
        if (text.isEmpty()) return emptyList<String>() to 0
        val body = text.removeSuffix("\n")
        val totalLines = body.count { it == '\n' } + 1
        var cutAt = body.length
        if (totalLines > MAX_DISPLAYED_LINES) {
            var newlinesSeen = 0
            for (i in body.indices) {
                if (body[i] == '\n' && ++newlinesSeen == MAX_DISPLAYED_LINES) {
                    cutAt = i
                    break
                }
            }
        }
        // Split on \n only (matching the count); a CRLF line ending loses its \r, a stray \r stays visible
        return body.substring(0, cutAt).split('\n').map { it.removeSuffix("\r") } to totalLines
    }

    /** HTML-escapes a line, writing every character outside printable ASCII (tab aside) as a numeric entity. */
    internal fun escape(line: String): String = buildString(line.length) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c.code in FIRST_PRINTABLE_ASCII..LAST_PRINTABLE_ASCII -> append(c)
                c.isHighSurrogate() && i + 1 < line.length && line[i + 1].isLowSurrogate() -> {
                    val codePoint = ((c.code - 0xD800) shl 10) + (line[i + 1].code - 0xDC00) + 0x10000
                    append("&#x").append(codePoint.toString(16)).append(';')
                    i++
                }
                else -> append("&#x").append(c.code.toString(16)).append(';')
            }
            i++
        }
    }
}
