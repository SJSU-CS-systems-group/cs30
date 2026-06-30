package html

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import theme.CodeFont
import theme.MonoTextStyle

@Composable
fun ProblemHtmlRenderer(html: String, modifier: Modifier = Modifier) {
    val blocks = parseProblemHtml(html)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is HtmlBlock.Heading -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                is HtmlBlock.Subheading -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                )
                is HtmlBlock.Paragraph -> Text(
                    text = richText(block.html, CodeFont),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                is HtmlBlock.Code -> Text(
                    text = block.text,
                    style = MonoTextStyle.copy(color = Color(0xFF111111)),
                    modifier = Modifier
                        .background(Color(0xFFF6F6F6))
                        .padding(8.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private sealed interface HtmlBlock {
    data class Heading(val text: String) : HtmlBlock
    data class Subheading(val text: String) : HtmlBlock
    data class Paragraph(val html: String) : HtmlBlock
    data class Code(val text: String) : HtmlBlock
}

private fun parseProblemHtml(html: String): List<HtmlBlock> {
    val cleaned = html
        .replace(Regex("(?is)<script.*?</script>"), "")
        .replace(Regex("(?is)<style.*?</style>"), "")
        .replace(Regex("(?is)</?(html|head|body|meta|link)[^>]*>"), "")

    val blocks = mutableListOf<HtmlBlock>()
    val blockRegex = Regex("(?is)<(h[1-6]|p|pre|div|section|article|li|td|th)[^>]*>(.*?)</\\1>")
    blockRegex.findAll(cleaned).forEach { match ->
        val tag = match.groupValues[1].lowercase()
        val content = match.groupValues[2].trim()
        val text = stripTags(content).trim()
        if (text.isBlank()) return@forEach
        when {
            tag == "h1" -> blocks += HtmlBlock.Heading(text)
            tag.startsWith("h") -> blocks += HtmlBlock.Subheading(text)
            tag == "pre" -> blocks += HtmlBlock.Code(text)
            else -> blocks += HtmlBlock.Paragraph(content)
        }
    }

    return blocks.ifEmpty {
        stripTags(cleaned)
            .split(Regex("\\n{2,}"))
            .mapNotNull { paragraph ->
                paragraph.trim().takeIf { it.isNotBlank() }?.let { HtmlBlock.Paragraph(it) }
            }
    }
}

private fun richText(html: String, codeFont: FontFamily) = buildAnnotatedString {
    val tokenRegex = Regex("(?is)<(b|strong|i|em|code)[^>]*>(.*?)</\\1>")
    var index = 0
    tokenRegex.findAll(html).forEach { match ->
        append(decodeEntities(stripTags(html.substring(index, match.range.first))))
        val tag = match.groupValues[1].lowercase()
        val text = decodeEntities(stripTags(match.groupValues[2]))
        val style = when (tag) {
            "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
            "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
            "code" -> SpanStyle(fontFamily = codeFont, background = Color(0xFFF0F0F0))
            else -> SpanStyle()
        }
        withStyle(style) { append(text) }
        index = match.range.last + 1
    }
    append(decodeEntities(stripTags(html.substring(index))))
}

private fun stripTags(value: String): String =
    decodeEntities(
        value
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
    )

private fun decodeEntities(value: String): String =
    value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()
