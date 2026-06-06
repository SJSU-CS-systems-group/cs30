package lockdown

actual fun clearSystemClipboard() {
    writeClipboard(CLIPBOARD_SENTINEL)
}

private fun writeClipboard(text: String): Unit =
    js("{ try { if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text); } } catch (err) {} }")
