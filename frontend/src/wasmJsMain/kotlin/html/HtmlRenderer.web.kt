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
        // Static base styles set once; geometry (updatePosition) and pointer-events
        // (setInteractive) are then set as individual properties so they never clobber each other.
        iframe = (document.createElement("iframe") as HTMLIFrameElement).apply {
            style.cssText = "position:absolute;border:none;margin:0;padding:0;display:block;pointer-events:auto;"
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
        } catch (e: Exception) {
            println("[HtmlRenderer-Web] ⚠️ Error removing iframe: ${e.message}")
        }
    }

    actual fun loadHtml(html: String, css: String, interactive: Boolean, theme: HtmlTheme) {
        try {
            iframe.style.setProperty("background-color", theme.background) // avoid white flash
            val fullHtml = HtmlDocument.build(html, css, theme)
            iframe.setAttribute("srcdoc", fullHtml)
        } catch (e: Exception) {
            println("[HtmlRenderer-Web] ❌ ERROR in loadHtml: ${e.message}")
            e.printStackTrace()
        }
    }

    actual fun setInteractive(interactive: Boolean) {
        // Set as an individual property so a concurrent updatePosition() can't reset it.
        iframe.style.setProperty("pointer-events", if (interactive) "auto" else "none")
    }

    fun updatePosition(top: Int, left: Int, width: Int, height: Int) {
        if (top == currentTop && left == currentLeft && width == currentWidth && height == currentHeight) {
            return  // Avoid redundant updates
        }
        currentTop = top
        currentLeft = left
        currentWidth = width
        currentHeight = height

        iframe.style.setProperty("top", "${top}px")
        iframe.style.setProperty("left", "${left}px")
        iframe.style.setProperty("width", "${width}px")
        iframe.style.setProperty("height", "${height}px")
    }
}
