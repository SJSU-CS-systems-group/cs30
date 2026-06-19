package html

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView

actual class HtmlRenderer {
    val jfxPanel: JFXPanel
    private var webView: WebView? = null

    init {
        Platform.setImplicitExit(false)
        println("[HtmlRenderer-Desktop] 🔨 Creating JFXPanel (ImplicitExit=false to keep FX thread alive)")
        System.out.flush()
        jfxPanel = JFXPanel()
        println("[HtmlRenderer-Desktop] ✓ JFXPanel created")
        System.out.flush()

        println("[HtmlRenderer-Desktop] 🧵 Scheduling blank WebView init on FX thread")
        System.out.flush()
        Platform.runLater {
            println("[HtmlRenderer-Desktop] 🧵 FX THREAD: creating blank WebView")
            System.out.flush()
            val view = WebView()
            view.isContextMenuEnabled = false
            webView = view
            val pane = StackPane(view)
            pane.style = "-fx-background-color: white;"
            jfxPanel.scene = Scene(pane)
            println("[HtmlRenderer-Desktop] ✅ FX THREAD: blank scene ready")
            System.out.flush()
        }
    }

    actual fun setInteractive(interactive: Boolean) {
        // Let pointer events pass through the WebView during a divider drag.
        Platform.runLater { webView?.isMouseTransparent = !interactive }
    }

    actual fun loadHtml(html: String, css: String, interactive: Boolean) {
        println("[HtmlRenderer-Desktop] 📋 loadHtml called (${html.length}c), thread: ${Thread.currentThread().name}")
        System.out.flush()

        val fullHtml = HtmlDocument.build(html, css)

        println("[HtmlRenderer-Desktop] 📤 About to call Platform.runLater")
        System.out.flush()
        try {
            Platform.runLater {
                println("[HtmlRenderer-Desktop] 🧵 FX THREAD: loadContent into WebView (${fullHtml.length}c)")
                System.out.flush()
                try {
                    val view = webView
                    if (view != null) {
                        view.isContextMenuEnabled = interactive
                        view.engine.loadContent(fullHtml, "text/html")
                        println("[HtmlRenderer-Desktop] ✅ FX THREAD: loadContent called")
                        System.out.flush()
                    } else {
                        println("[HtmlRenderer-Desktop] ⚠️  FX THREAD: WebView is null, skipping")
                        System.out.flush()
                    }
                } catch (e: Exception) {
                    println("[HtmlRenderer-Desktop] ❌ FX ERROR in callback: ${e.message}")
                    e.printStackTrace()
                    System.out.flush()
                }
            }
            println("[HtmlRenderer-Desktop] ✓ Platform.runLater call returned (queued)")
            System.out.flush()
        } catch (e: Exception) {
            println("[HtmlRenderer-Desktop] ❌ ERROR calling Platform.runLater: ${e.message}")
            e.printStackTrace()
            System.out.flush()
        }
    }
}
