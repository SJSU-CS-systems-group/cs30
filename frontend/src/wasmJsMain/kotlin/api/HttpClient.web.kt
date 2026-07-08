package backend

import auth.ApiToken
import kotlinx.coroutines.await
import kotlin.js.Promise
import org.w3c.fetch.Response

/**
 * Awaited same-origin HTTP POST for the web target. Matches desktop's blocking behavior so
 * the request actually completes before the coroutine returns (e.g. the activity session-end
 * commit). Auth is a Bearer token (same mechanism as desktop), attached when present.
 * Failures are logged and swallowed (like desktop's runCatching) so one failed POST can't
 * cancel the caller's loop.
 */
actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int {
    val url = baseUrl + path
    println("[Http-Web] POST $url")
    return try {
        val response: Response = fetchPost(url, body, authHeader).await()
        println("[Http-Web] POST $url -> ${response.status}")
        response.status.toInt()
    } catch (e: Throwable) {
        println("[Http-Web] POST $url FAILED: ${e.message}")
        -1
    }
}

private fun fetchPost(url: String, body: String, authHeader: String?): Promise<Response> =
    js("fetch(url, { method:'POST', headers: authHeader ? {'Content-Type':'application/json','Authorization':authHeader} : {'Content-Type':'application/json'}, body:body })")

actual suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String =
    fetchPostText(baseUrl + path, body, authHeader).await<JsString>().toString()

private fun fetchPostText(url: String, body: String, authHeader: String?): Promise<JsString> =
    js("fetch(url, { method:'POST', headers: authHeader ? {'Content-Type':'application/json','Authorization':authHeader} : {'Content-Type':'application/json'}, body:body }).then(function(r){ return r.text(); })")

actual suspend fun getJsonWithResponse(url: String, authHeader: String?): String =
    fetchGetText(url, authHeader).await<JsString>().toString()

private fun fetchGetText(url: String, authHeader: String?): Promise<JsString> =
    js("fetch(url, { method:'GET', headers: authHeader ? {'Accept':'application/json','Authorization':authHeader} : {'Accept':'application/json'} }).then(function(r){ return r.text(); })")

// Web now uses the same Bearer-token mechanism as desktop instead of the session cookie.
actual fun getCurrentAuthHeader(): String? = ApiToken.value?.let { "Bearer $it" }
