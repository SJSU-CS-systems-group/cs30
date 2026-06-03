package labx.html

object HtmlDocument {
    fun build(bodyHtml: String, css: String): String {
        var cleaned = bodyHtml
            .replace(Regex("(?i)<link[^>]*>"), "")
            .replace(Regex("(?i)<script.*?</script>"), "")
        cleaned = HtmlNormalizer.normalize(cleaned)

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { margin: 0; padding: 0; }
                    $css
                </style>
            </head>
            <body>$cleaned</body>
            </html>
        """.trimIndent()
    }
}
