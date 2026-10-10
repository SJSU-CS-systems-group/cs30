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

    /** Material "wrap text" icon, inline: the bridge strips scripts and has no icon assets. */
    private const val WRAP_ICON =
        """<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">""" +
            """<path d="M4 19h6v-2H4v2zM20 5H4v2h16V5zm-3 6H4v2h13.25c1.1 0 2 .9 2 2s-.9 2-2 2H15v-2l-3 3 3 3v-2h2c2.21 0 4-1.79 4-4s-1.79-4-4-4z"/></svg>"""

    /**
     * Passed as the panel's css; overrides the problem-statement padding/scrolling of the bridge.
     * Each line is its own row (number + text) so a wrapped line keeps its number beside it. The wrap
     * button is a label for a hidden checkbox - pure CSS, since the bridge strips scripts - so toggling
     * never reloads the page or loses the scroll position.
     */
    const val CSS = """
        .problem-container { padding: 0 !important; overflow: hidden !important; height: 100%; }
        .tc-columns { display: flex; gap: 16px; height: 100%; padding: 8px; box-sizing: border-box; }
        .tc-column { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; }
        .tc-header { display: flex; align-items: center; justify-content: space-between; margin: 0 0 4px; }
        .tc-label { font: 600 13px system-ui, sans-serif; margin: 0; }
        .tc-notice { font: 12px system-ui, sans-serif; opacity: 0.7; margin: 0 0 4px; }
        .tc-wrap-toggle { position: absolute; opacity: 0; width: 0; height: 0; pointer-events: none; }
        .tc-wrap-button { display: inline-flex; padding: 3px; border-radius: 4px; cursor: pointer; opacity: 0.6; }
        .tc-wrap-button:hover { opacity: 1; background: rgba(127, 127, 127, 0.18); }
        .tc-wrap-toggle:checked ~ .tc-header .tc-wrap-button { opacity: 1; background: rgba(127, 127, 127, 0.3); }
        .tc-wrap-toggle:focus-visible ~ .tc-header .tc-wrap-button { outline: 2px solid currentColor; }
        .tc-scroll {
            flex: 1; overflow: auto; margin: 0; padding: 0; border-radius: 6px;
            font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        }
        .tc-lines { display: block; width: max-content; min-width: 100%; background-color: inherit; }
        .tc-line { display: flex; background-color: inherit; }
        .tc-num {
            position: sticky; left: 0; flex: none; width: var(--num-width); padding: 0 8px;
            text-align: right; user-select: none; background-color: inherit;
            background-image: linear-gradient(rgba(127, 127, 127, 0.14), rgba(127, 127, 127, 0.14));
            border-right: 1px solid rgba(127, 127, 127, 0.45);
            color: color-mix(in srgb, currentColor 60%, transparent);
        }
        .tc-text { padding: 0 8px; white-space: pre; }
        .tc-wrap-toggle:checked ~ .tc-scroll .tc-lines { width: auto; }
        .tc-wrap-toggle:checked ~ .tc-scroll .tc-text { flex: 1; min-width: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    """

    fun body(input: String, expected: String): String =
        """<div class="tc-columns">${column("input", "Input", input)}${column("expected", "Expected output", expected)}</div>"""

    private fun column(id: String, label: String, text: String): String {
        val (shownLines, totalLines) = firstLines(text)
        val notice = if (totalLines > shownLines.size) {
            """<p class="tc-notice">Showing first ${shownLines.size} of $totalLines lines</p>"""
        } else ""
        val numberWidth = shownLines.size.toString().length
        val rows = shownLines.withIndex().joinToString("") { (index, line) ->
            """<span class="tc-line"><span class="tc-num">${index + 1}</span><span class="tc-text">${escape(line)}</span></span>"""
        }
        // The checkbox precedes the header and the scroll area so the CSS `~` selectors can react to it
        return """<div class="tc-column">""" +
            """<input type="checkbox" id="wrap-$id" class="tc-wrap-toggle">""" +
            """<div class="tc-header"><p class="tc-label">$label · $totalLines lines</p>""" +
            """<label for="wrap-$id" class="tc-wrap-button" title="Wrap long lines" aria-label="Wrap long lines">$WRAP_ICON</label></div>""" +
            notice +
            // A <pre> so the bridge's theme gives it the code background, which the rows and sticky numbers inherit
            """<pre class="tc-scroll"><span class="tc-lines" style="--num-width: ${numberWidth}ch">$rows</span></pre>""" +
            """</div>"""
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
