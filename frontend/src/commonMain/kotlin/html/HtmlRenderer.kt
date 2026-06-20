package html

expect class HtmlRenderer() {
    fun loadHtml(html: String, css: String, interactive: Boolean, theme: HtmlTheme = HtmlTheme.DEFAULT)

    /**
     * Toggles pointer capture of the rendered surface without reloading. Web toggles the
     * iframe's pointer-events; desktop toggles the WebView's mouse transparency. Lets a
     * divider drag pass through the HTML surface instead of being swallowed by it.
     */
    fun setInteractive(interactive: Boolean)
}
