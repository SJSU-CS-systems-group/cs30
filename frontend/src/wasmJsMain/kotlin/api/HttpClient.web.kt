package backend

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Awaited same-origin HTTP POST for the web target. Matches desktop's blocking behavior so
 * the request actually completes before the coroutine returns (e.g. the activity session-end
 * commit). The js() helper returns the fetch Promise so Kotlin can await it; cookies are sent
 * via credentials:'same-origin'. authHeader is unused on web (cookie-based auth).
 */
actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?) {
    fetchPost(baseUrl + path, body).await<JsAny?>()
}

private fun fetchPost(url: String, body: String): Promise<JsAny?> =
    js("fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:body, credentials:'same-origin' })")

actual suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String =
    fetchPostText(baseUrl + path, body).await<JsString>().toString()

private fun fetchPostText(url: String, body: String): Promise<JsString> =
    js("fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:body, credentials:'same-origin' }).then(function(r){ return r.text(); })")

// Web uses session cookies for auth, so no Bearer token is needed
actual fun getCurrentAuthHeader(): String? = null
