package labx.html

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView

@Composable
actual fun HtmlText(html: String, modifier: Modifier) {
    SwingPanel(
        modifier = modifier,
        factory = {
            // Constructing JFXPanel bootstraps the JavaFX runtime on first use.
            val jfxPanel = JFXPanel()
            Platform.setImplicitExit(false)
            Platform.runLater {
                val webView = WebView()
                webView.engine.loadContent(html)
                jfxPanel.scene = Scene(webView)
            }
            jfxPanel
        },
        update = { jfxPanel ->
            Platform.runLater {
                val webView = jfxPanel.scene?.root as? WebView ?: return@runLater
                webView.engine.loadContent(html)
            }
        }
    )
}
