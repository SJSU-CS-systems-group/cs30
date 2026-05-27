package labx.html

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

@Composable
actual fun HtmlText(html: String, modifier: Modifier) {
    Box(modifier = modifier) {
        DisposableEffect(html) {
            val body = document.body ?: return@DisposableEffect onDispose {}
            val div = document.createElement("div") as HTMLElement
            div.setAttribute("style",
                "position:absolute; top:0; left:0; width:100%; height:100%; overflow:auto; background:white; z-index:10;"
            )
            div.innerHTML = html
            body.appendChild(div)
            onDispose {
                body.removeChild(div)
            }
        }
    }
}
