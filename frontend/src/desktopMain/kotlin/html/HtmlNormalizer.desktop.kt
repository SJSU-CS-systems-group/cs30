package html

import java.text.Normalizer

/**
 * Desktop (JVM) implementation of Unicode NFC normalization.
 */
actual fun normalizeUnicodeNFC(text: String): String {
    return Normalizer.normalize(text, Normalizer.Form.NFC)
}
