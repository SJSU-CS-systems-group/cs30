package labx.backend

import kotlinx.browser.window
import kotlinx.coroutines.await

actual suspend fun getJson(baseUrl: String, path: String): String {
    val response = window.fetch(baseUrl + path, object {
        val credentials = "same-origin"
    }).await()
    return response.text().await()
}
