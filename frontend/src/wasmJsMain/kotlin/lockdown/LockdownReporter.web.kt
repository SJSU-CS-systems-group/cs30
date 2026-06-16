package lockdown

import kotlinx.coroutines.await
import kotlin.js.Promise

actual val defaultReporterBaseUrl: String = ""  // same-origin

/**
 * Awaited same-origin POST so lockdown violations are reliably delivered (no fire-and-forget).
 * The js() helper returns the fetch Promise so Kotlin can await it; cookies are sent via
 * credentials:'same-origin'.
 */
actual suspend fun postJson(baseUrl: String, path: String, body: String) {
    fetchPost(baseUrl + path, body).await<JsAny?>()
}

private fun fetchPost(url: String, body: String): Promise<JsAny?> =
    js("fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:body, credentials:'same-origin' })")
