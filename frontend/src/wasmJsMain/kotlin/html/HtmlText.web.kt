package html

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
actual fun HtmlText(
    html: String,
    css: String,
    renderer: HtmlRenderer,
    modifier: Modifier,
    interactive: Boolean
) {
    if (html.isEmpty() || css.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    DisposableEffect(Unit) {
        println("[HtmlText-Web] 📎 HtmlText composable entered")

        onDispose {
            println("[HtmlText-Web] 🧹 HtmlText composable exiting, cleaning up iframe")
            (renderer as HtmlRenderer).cleanup()
        }
    }

    LaunchedEffect(html, css) {
        println("[HtmlText-Web] 📋 Loading content via renderer")
        renderer.loadHtml(html, css, interactive)
    }

    LaunchedEffect(modifier) {
        try {
            val docElement = kotlinx.browser.document.documentElement ?: return@LaunchedEffect
            val viewportWidth = docElement.clientWidth
            val viewportHeight = docElement.clientHeight

            val topBarHeight = 64
            val panelWidth = 320
            val topPx = topBarHeight
            val leftPx = 0
            val widthPx = panelWidth
            val heightPx = viewportHeight - topBarHeight

            (renderer as HtmlRenderer).updatePosition(topPx, leftPx, widthPx, heightPx)
            println("[HtmlText-Web] 📐 Position set: ${topPx}px ${leftPx}px ${widthPx}px×${heightPx}px (viewport: ${viewportWidth}×${viewportHeight})")
        } catch (e: Exception) {
            println("[HtmlText-Web] ⚠️ Error setting position: ${e.message}")
        }
    }

    Box(modifier = modifier.fillMaxSize())
}

