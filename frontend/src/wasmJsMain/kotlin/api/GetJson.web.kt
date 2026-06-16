package backend

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.Response

/**
 * Real same-origin HTTP GET for the web target.
 *
 * The frontend is served by the backend, so a relative fetch ("" baseUrl + path) stays
 * same-origin and the browser sends the JSESSIONID session cookie automatically — that is
 * how the backend identifies the student. authHeader is unused on web (cookie-based auth).
 *
 * Mirrors GetJson.desktop.kt: 404 -> NOT_ENROLLED, other non-2xx -> error, else body text.
 */
actual suspend fun getJson(baseUrl: String, path: String, authHeader: String?): String {
    val response: Response = window.fetch(baseUrl + path).await()
    when {
        response.status.toInt() == HTTP_NOT_FOUND -> throw NoSuchElementException("NOT_ENROLLED")
        !response.ok -> throw RuntimeException("HTTP ${response.status} loading " + path)
    }
    return response.text().await<JsString>().toString()
}

private const val HTTP_NOT_FOUND = 404
