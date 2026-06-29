package backend

import kotlinx.coroutines.await
import kotlin.js.Promise
import org.w3c.fetch.Response

/**
 * Awaited same-origin HTTP POST for the web target. Matches desktop's blocking behavior so
 * the request actually completes before the coroutine returns (e.g. the activity session-end
 * commit). Cookies are sent via credentials:'same-origin'. authHeader is unused on web
 * (cookie-based auth). Failures are logged and swallowed (like desktop's runCatching) so one
 * failed POST can't cancel the caller's loop.
 */
actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int {
    val url = baseUrl + path
    println("[Http-Web] POST $url")
    return try {
        val response: Response = fetchPost(url, body).await()
        println("[Http-Web] POST $url -> ${response.status}")
        response.status.toInt()
    } catch (e: Throwable) {
        println("[Http-Web] POST $url FAILED: ${e.message}")
        -1
    }
}

private fun fetchPost(url: String, body: String): Promise<Response> =
    js("fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:body, credentials:'same-origin' })")

actual suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String =
    fetchPostText(baseUrl + path, body).await<JsString>().toString()

private fun fetchPostText(url: String, body: String): Promise<JsString> =
    js("fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:body, credentials:'same-origin' }).then(function(r){ return r.text(); })")

actual suspend fun getJsonWithResponse(url: String, authHeader: String?): String =
    fetchGetText(url).await<JsString>().toString()

private fun fetchGetText(url: String): Promise<JsString> =
    js("fetch(url, { method:'GET', headers:{'Accept':'application/json'}, credentials:'same-origin' }).then(function(r){ return r.text(); })")

// Web uses session cookies for auth, so no Bearer token is needed
actual fun getCurrentAuthHeader(): String? = null
