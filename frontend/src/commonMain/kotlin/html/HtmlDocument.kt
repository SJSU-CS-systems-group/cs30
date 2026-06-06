package html

object HtmlDocument {
    fun build(bodyHtml: String, css: String): String {
        var cleaned = bodyHtml
            .replace(Regex("(?i)<link[^>]*>"), "")
            .replace(Regex("(?i)<script.*?</script>"), "")
        cleaned = HtmlNormalizer.normalize(cleaned)

        return """
            <!DOCTYPE html>
            <html lang="en" style="height: 100%;">
            <head>
                <meta charset="UTF-8">
                <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    html { height: 100%; }
                    body { margin: 0; padding: 0; height: 100%; }
                    .problem-container {
                        padding: 16px 20px;
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
