@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package html

import cs30.frontend.generated.resources.Res

/**
 * Normalizes HTML content to fix encoding, smart punctuation, and whitespace issues
 * without breaking MathJax, CSS, tables, or code blocks.
 */
object HtmlNormalizer {
    private var debugEnabled = false

    fun setDebugMode(enabled: Boolean) {
        debugEnabled = enabled
    }

    /**
     * Strips HTML down to plain visible text. Used only to check whether pasted clipboard text
     * was copied from a rendered problem statement — not for display. Not a full HTML parser:
     * script/style contents are dropped, all other tags are removed, a handful of common
     * entities are decoded, and whitespace is collapsed to single spaces.
     */
    fun toPlainText(html: String): String {
        var text = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Normalize HTML before rendering:
     * - Unicode NFC normalization (JVM only, skipped on wasmJs)
     * - Smart punctuation replacement
     * - Control character removal
     * - Whitespace normalization (preserve in <pre>, <code>, <script>, <style>)
     * - Logging of suspicious characters
     */
    fun normalize(html: String): String {
        var normalized = html

        // Step 1: Unicode NFC normalization (platform-specific)
        normalized = normalizeUnicodeNFC(normalized)

        // Step 2: Replace smart punctuation and dashes
        normalized = fixSmartPunctuation(normalized)

        // Step 3: Remove control characters except tab and newline
        normalized = removeControlCharacters(normalized)

        // Step 4: Normalize whitespace (preserve in special tags)
        normalized = normalizeWhitespace(normalized)

        // Step 5: Debug logging for suspicious characters
        if (debugEnabled) {
            logSuspiciousCharacters(normalized)
        }

        return normalized
    }

    /**
     * Replace smart/curly punctuation with ASCII equivalents.
     */
    private fun fixSmartPunctuation(html: String): String {
        var result = html

        // Curly quotes → straight quotes
        result = result.replace(""", "\"")  // U+201C left double quotation mark
        result = result.replace(""", "\"")  // U+201D right double quotation mark
        result = result.replace("'", "'")   // U+2018 left single quotation mark
        result = result.replace("'", "'")   // U+2019 right single quotation mark

        // Dashes: en-dash and em-dash → hyphen-minus
        result = result.replace("–", "-")   // U+2013 en dash
        result = result.replace("—", "-")   // U+2014 em dash

        // Other common smart characters
        result = result.replace("…", "...")  // U+2026 horizontal ellipsis
        result = result.replace("‚", ",")    // U+201A single low-9 quotation mark

        // Non-breaking spaces: convert to regular space outside of <pre>
        // This is context-aware: preserve in <pre>, convert elsewhere
        result = fixNonBreakingSpaces(result)

        return result
    }

    /**
     * Convert non-breaking spaces (&nbsp; and U+00A0) to regular spaces,
     * but preserve them inside <pre> tags.
     */
    private fun fixNonBreakingSpaces(html: String): String {
        // Find all <pre>...</pre> blocks and protect them
        val preBlocks = mutableListOf<String>()
        val preRegex = Regex("<pre[^>]*>[\\s\\S]*?</pre>")

        var result = html
        var preCount = 0

        result = result.replace(preRegex) { matchResult ->
            val placeholder = "<<<PRE_BLOCK_$preCount>>>"
            preBlocks.add(matchResult.value)
            preCount++
            placeholder
        }

        // Now convert non-breaking spaces outside of <pre>
        result = result.replace("&nbsp;", " ")
        result = result.replace(" ", " ")  // U+00A0 non-breaking space

        // Restore <pre> blocks
        preBlocks.forEachIndexed { index, block ->
            result = result.replace("<<<PRE_BLOCK_$index>>>", block)
        }

        return result
    }

    /**
     * Remove control characters (except tab, newline, carriage return).
     * Replace U+FFFD (replacement character) with a safe marker.
     */
    private fun removeControlCharacters(html: String): String {
        return html.map { char ->
            when {
                char == '\t' || char == '\n' || char == '\r' -> char  // Keep these
                char == '�' -> '?'  // Replacement char → question mark
                char.code < 32 -> ' '    // Other controls → space
                char.code == 127 -> ' '  // DEL → space
                else -> char
            }
        }.joinToString("")
    }

    /**
     * Normalize whitespace:
     * - Collapse multiple spaces to single space
     * - Convert line breaks between words to single space (outside <pre>, <code>)
     * - Preserve whitespace structure in <pre>, <code>, <script>, <style>
     */
    private fun normalizeWhitespace(html: String): String {
        // Identify protected blocks
        val protectedBlocks = mutableListOf<String>()
        var result = html
        var blockCount = 0

        // Protect <pre>, <code>, <script>, <style> blocks
        val protectionRegex = Regex(
            "<(pre|code|script|style)[^>]*>[\\s\\S]*?</\\1>",
            RegexOption.IGNORE_CASE
        )

        result = result.replace(protectionRegex) { matchResult ->
            val placeholder = "<<<PROTECTED_BLOCK_$blockCount>>>"
            protectedBlocks.add(matchResult.value)
            blockCount++
            placeholder
        }

        // Now normalize whitespace outside protected blocks
        // Collapse multiple spaces to single space
        result = result.replace(Regex("  +"), " ")

        // Convert newlines + optional spaces + newlines to newline
        // (preserves paragraph breaks)
        result = result.replace(Regex("\n\\s*\n"), "\n\n")

        // Convert single newlines with surrounding spaces to single space
        // This fixes: "word\n    nextword" → "word nextword"
        result = result.replace(Regex("\\s*\n\\s*"), " ")

        // Clean up extra spaces around tags
        result = result.replace(Regex(">\\s+<"), "><")

        // Restore protected blocks
        protectedBlocks.forEachIndexed { index, block ->
            result = result.replace("<<<PROTECTED_BLOCK_$index>>>", block)
        }

        return result
    }


    /**
     * Log suspicious characters for debugging.
     * Reports: replacement chars, control chars, zero-width chars, non-breaking spaces.
     */
    private fun logSuspiciousCharacters(html: String) {
        val suspiciousChars = mutableListOf<String>()

        html.forEachIndexed { index, char ->
            val code = char.code
            val suspicious = when {
                char == '�' -> "Replacement character (U+FFFD)"
                code < 32 && char !in setOf('\t', '\n', '\r') -> "Control character (U+${code.toString(16).padStart(4, '0')})"
                code in listOf(0x200B, 0x200C, 0x200D) -> "Zero-width character (U+${code.toString(16).padStart(4, '0')})"
                code == 0x00A0 -> "Non-breaking space (U+00A0)"
                code in listOf(0x2018, 0x2019, 0x201C, 0x201D) -> "Smart quote (U+${code.toString(16).padStart(4, '0')})"
                code in listOf(0x2013, 0x2014) -> "Dash character (U+${code.toString(16).padStart(4, '0')})"
                else -> null
            }

            if (suspicious != null) {
                val startIdx = maxOf(0, index - 20)
                val endIdx = minOf(html.length, index + 20)
                val context = html.substring(startIdx, endIdx)
                    .replace("\n", "\\n")
                    .replace("\t", "\\t")
                suspiciousChars.add("$suspicious at position $index: \"$context\"")
            }
        }

        if (suspiciousChars.isNotEmpty()) {
            println("[HtmlNormalizer] Found suspicious characters:")
            suspiciousChars.forEach { println("  - $it") }
        }
    }
}

/**
 * Platform-specific Unicode NFC normalization.
 * JVM: uses java.text.Normalizer
 * wasmJs: returns string as-is (browser handles normalization)
 */
expect fun normalizeUnicodeNFC(text: String): String

/**
 * Load problem.css from resources (called once at screen level, not per-problem)
 */
suspend fun loadProblemCss(): String {
    return try {
        val bytes = Res.readBytes("files/problem.css")
        bytes.decodeToString()
    } catch (e: Exception) {
        println("[HtmlNormalizer] Error loading CSS: ${e.message}")
        ""
    }
}
