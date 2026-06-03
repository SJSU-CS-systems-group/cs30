package labx.html

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLIFrameElement

/**
 * Web implementation: renders problem HTML into a sandboxed iframe via srcdoc (memory-only, no files).
 * Spinner shown until the iframe is appended to the DOM.
 * Lifecycle: iframe created when composable enters composition (problem opened),
 * removed when it leaves (End Lab → screen unmounts).
 */
@Composable
actual fun HtmlText(
    html: String,
    css: String,
    renderer: HtmlRenderer,
    modifier: Modifier,
    interactive: Boolean
) {
    if (html.isEmpty() || css.isEmpty()) {
        Box(modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    // Reset to not-ready whenever html or css changes (new problem loaded)
    var isReady by remember(html, css) { mutableStateOf(false) }

    DisposableEffect(html, css) {
        println("[HtmlText-Web] 📋 Creating iframe with srcdoc (memory-only)")
        val fullHtml = HtmlDocument.build(html, css)
        val containerId = "html-problem-${fullHtml.hashCode()}"

        val container = (document.createElement("div") as HTMLDivElement).apply {
            id = containerId
            style.cssText = "width:100%;height:100%;"
        }
        val iframe = (document.createElement("iframe") as HTMLIFrameElement).apply {
            style.cssText = "border:none;width:100%;height:100%;display:block;"
            setAttribute("srcdoc", fullHtml)
        }
        container.appendChild(iframe)
        document.body?.appendChild(container)
        isReady = true
        println("[HtmlText-Web] ✅ iframe appended — spinner dismissed")

        onDispose {
            println("[HtmlText-Web] 🧹 Disposing iframe container")
            try { document.body?.removeChild(container) } catch (_: Exception) {}
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!isReady) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

