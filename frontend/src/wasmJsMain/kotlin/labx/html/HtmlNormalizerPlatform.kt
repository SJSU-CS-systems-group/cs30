package labx.html

/**
 * Web (wasmJs) implementation of Unicode normalization.
 * Wasmjs doesn't have java.text.Normalizer, and the browser/DOM
 * handles Unicode normalization natively, so this is a no-op.
 */
actual fun normalizeUnicodeNFC(text: String): String {
    // wasmJs: return as-is, browser handles encoding natively
    return text
}
