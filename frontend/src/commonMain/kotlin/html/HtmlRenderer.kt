package html

expect class HtmlRenderer() {
    fun loadHtml(html: String, css: String, interactive: Boolean)
}
