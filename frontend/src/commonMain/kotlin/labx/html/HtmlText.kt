package labx.html

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HtmlText(
    html: String,
    css: String,
    renderer: HtmlRenderer,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
)
