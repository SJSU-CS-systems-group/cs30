package html

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

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
        onDispose {
            (renderer as HtmlRenderer).cleanup()
        }
    }

    LaunchedEffect(html, css) {
        renderer.loadHtml(html, css, interactive)
    }

    // The iframe lives in a DOM overlay (#htmlOverlay) positioned in CSS pixels, detached from
    // the Compose layout. Track this Box's real bounds and mirror them onto the iframe so it
    // fills the problem panel and follows resizes — instead of a hardcoded width. Compose
    // reports layout in Compose pixels; divide by density to convert to the overlay's CSS pixels.
    val density = LocalDensity.current.density
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val size = coordinates.size
                (renderer as HtmlRenderer).updatePosition(
                    top = (position.y / density).toInt(),
                    left = (position.x / density).toInt(),
                    width = (size.width / density).toInt(),
                    height = (size.height / density).toInt()
                )
            }
    )
}
