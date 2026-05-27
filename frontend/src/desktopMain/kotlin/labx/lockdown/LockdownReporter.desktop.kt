package labx.lockdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual val defaultReporterBaseUrl: String = "http://localhost:8080"

actual suspend fun postJson(baseUrl: String, path: String, body: String) {
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode  // trigger send
            conn.disconnect()
        }
    }
}
