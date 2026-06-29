package backend

import auth.ApiToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int =
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
            val code = conn.responseCode
            conn.disconnect()
            code
        }.getOrDefault(-1)
    }

actual suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String =
    withContext(Dispatchers.IO) {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            authHeader?.let { setRequestProperty("Authorization", it) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        // Read the body for both 2xx and handled errors (the backend returns its
        // RunCodeResponse/SubmitCodeResponse JSON even on 400).
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        text
    }

actual suspend fun getJsonWithResponse(url: String, authHeader: String?): String =
    withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            authHeader?.let { setRequestProperty("Authorization", it) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        text
    }

actual fun getCurrentAuthHeader(): String? = ApiToken.value?.let { "Bearer $it" }
