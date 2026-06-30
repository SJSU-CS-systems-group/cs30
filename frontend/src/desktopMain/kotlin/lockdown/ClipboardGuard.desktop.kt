package lockdown

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual fun clearSystemClipboard() {
    runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        cb.setContents(StringSelection(CLIPBOARD_SENTINEL), null)
    }
}

actual fun copyToClipboard(text: String) {
    runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        cb.setContents(StringSelection(text), null)
    }
}
