package html

object HtmlDocument {
    fun build(bodyHtml: String, css: String, theme: HtmlTheme = HtmlTheme.DEFAULT): String {
        var cleaned = bodyHtml
            .replace(Regex("(?i)<link[^>]*>"), "")
            .replace(Regex("(?i)<script.*?</script>"), "")
        cleaned = HtmlNormalizer.normalize(cleaned)

        // Theme base styles come BEFORE $css so a backend problem.css can still tweak details;
        // body bg/fg use !important so the panel always matches the app theme (light text on dark).
        return """
            <!DOCTYPE html>
            <html lang="en" style="height: 100%;">
            <head>
                <meta charset="UTF-8">
                <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    html { height: 100%; }
                    html, body { background: ${theme.background} !important; color: ${theme.foreground} !important; }
                    body { margin: 0; padding: 0; height: 100%; }
                    /* Theme the statement scrollbar so it doesn't show the browser/WebView default
                       (a bright bar in dark themes). WebKit covers the JavaFX WebView + Chromium. */
                    ::-webkit-scrollbar { width: 10px; height: 10px; }
                    ::-webkit-scrollbar-track { background: ${theme.background}; }
                    ::-webkit-scrollbar-thumb { background: ${theme.border}; border-radius: 5px; }
                    * { scrollbar-color: ${theme.border} ${theme.background}; scrollbar-width: thin; }
                    a { color: ${theme.link}; }
                    pre, code, kbd, samp { background: ${theme.codeBackground}; color: ${theme.codeForeground}; }
                    table, th, td { border-color: ${theme.border}; }
                    .problem-container {
                        padding: 16px 32px 64px 20px; /* top right bottom left */
                        overflow-y: auto;
                        height: 100%;
                        box-sizing: border-box;
                    }
                    $css
                </style>
            </head>
            <body><div class="problem-container">$cleaned</div></body>
            </html>
        """.trimIndent()
    }
}
