package auth

internal object ApiToken {
    var value: String? = null
}

// Mirrors the token onto a plain JS global so code outside the Kotlin HTTP layer entirely
// (the raw heartbeat/beacon script in index.html) can attach it as a Bearer header too.
internal fun syncApiTokenToWindow(token: String?): Unit = js("window.__cs30ApiToken = token")
