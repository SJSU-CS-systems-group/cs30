package html

import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

actual class HtmlRenderer {
    private val iframe: HTMLIFrameElement
    private var currentTop = 0
    private var currentLeft = 0
    private var currentWidth = 0
    private var currentHeight = 0

    init {
        println("[HtmlRenderer-Web] 🔨 Creating iframe element")

        iframe = (document.createElement("iframe") as HTMLIFrameElement).apply {
            style.cssText = "border:none;margin:0;padding:0;display:block;"
        }

        val overlay = document.getElementById("htmlOverlay")
        if (overlay != null) {
            overlay.appendChild(iframe)
            println("[HtmlRenderer-Web] ✓ iframe created, appended to overlay")
        } else {
            println("[HtmlRenderer-Web] ⚠️ htmlOverlay container not found!")
        }
    }

    fun cleanup() {
        println("[HtmlRenderer-Web] 🧹 Cleaning up iframe")
        try {
            val overlay = document.getElementById("htmlOverlay")
            overlay?.removeChild(iframe)
            println("[HtmlRenderer-Web] ✓ iframe removed from overlay")
        } catch (e: Exception) {
            println("[HtmlRenderer-Web] ⚠️ Error removing iframe: ${e.message}")
        }
    }

    actual fun loadHtml(html: String, css: String, interactive: Boolean) {
        println("[HtmlRenderer-Web] 📋 loadHtml called (${html.length}c)")

        try {
            val fullHtml = HtmlDocument.build(html, css)
            iframe.setAttribute("srcdoc", fullHtml)
            println("[HtmlRenderer-Web] ✅ srcdoc set (${fullHtml.length}c)")
        } catch (e: Exception) {
            println("[HtmlRenderer-Web] ❌ ERROR in loadHtml: ${e.message}")
            e.printStackTrace()
        }
    }

    fun updatePosition(top: Int, left: Int, width: Int, height: Int) {
        if (top == currentTop && left == currentLeft && width == currentWidth && height == currentHeight) {
            return  // Avoid redundant updates
        }

        currentTop = top
        currentLeft = left
        currentWidth = width
        currentHeight = height

        iframe.style.cssText = """
            position: absolute;
            top: ${top}px;
            left: ${left}px;
            width: ${width}px;
            height: ${height}px;
            border: none;
            margin: 0;
            padding: 0;
            display: block;
            pointer-events: auto;
        """.trimIndent()

        println("[HtmlRenderer-Web] 📍 position updated: ${top}px ${left}px ${width}px×${height}px")
    }
}
