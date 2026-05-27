package labx.html

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun HtmlText(html: String, modifier: Modifier, interactive: Boolean) {
    ProblemHtmlRenderer(html = html, modifier = modifier)
}
