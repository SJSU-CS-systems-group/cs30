package labx.html

actual class HtmlRenderer {
    actual fun loadHtml(html: String, css: String, interactive: Boolean) {
        // Web implementation uses iframe in HtmlText.kt directly, not this renderer
    }
}
