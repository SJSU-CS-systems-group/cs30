package backend

import auth.ApiToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?) {
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
                authHeader?.let { setRequestProperty("Authorization", it) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        }
    }
}

actual fun getCurrentAuthHeader(): String? = ApiToken.value?.let { "Bearer $it" }
