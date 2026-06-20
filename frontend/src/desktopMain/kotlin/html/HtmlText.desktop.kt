package html

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel

@Composable
actual fun HtmlText(
    html: String,
    css: String,
    renderer: HtmlRenderer,
    modifier: Modifier,
    interactive: Boolean,
    theme: HtmlTheme
) {
    if (html.isEmpty() || css.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    LaunchedEffect(html, css, theme) {
        renderer.loadHtml(html, css, interactive, theme)
    }

    Box(modifier = modifier.fillMaxSize()) {
        SwingPanel(modifier = Modifier.fillMaxSize(), factory = { (renderer as HtmlRenderer).jfxPanel })
    }
}
