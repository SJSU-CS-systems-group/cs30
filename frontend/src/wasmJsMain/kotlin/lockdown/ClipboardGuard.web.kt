package lockdown

actual fun clearSystemClipboard() {
    writeClipboard(CLIPBOARD_SENTINEL)
}

actual fun copyToClipboard(text: String) {
    writeClipboard(text)
}

private fun writeClipboard(text: String): Unit =
    js("{ try { if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text); } } catch (err) {} }")
