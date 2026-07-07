package backend

import kotlinx.coroutines.await
import kotlin.js.Promise
import org.w3c.fetch.Response

/**
 * Real same-origin HTTP GET for the web target.
 *
 * The frontend is served by the backend, so a relative fetch ("" baseUrl + path) stays
 * same-origin. Auth is a Bearer token (same mechanism as desktop), attached when present.
 *
 * Mirrors GetJson.desktop.kt: 404 -> NOT_ENROLLED, other non-2xx -> error, else body text.
 */
actual suspend fun getJson(baseUrl: String, path: String, authHeader: String?): String {
    val url = baseUrl + path
    println("[Http-Web] GET $url")
    val response: Response = fetchGet(url, authHeader).await()
    println("[Http-Web] GET $url -> ${response.status}")
    when {
        response.status.toInt() == HTTP_NOT_FOUND -> throw NoSuchElementException("NOT_ENROLLED")
        !response.ok -> throw RuntimeException("HTTP ${response.status} loading " + path)
    }
    return response.text().await<JsString>().toString()
}

private fun fetchGet(url: String, authHeader: String?): Promise<Response> =
    js("fetch(url, authHeader ? { headers: { 'Authorization': authHeader } } : {})")

private const val HTTP_NOT_FOUND = 404
