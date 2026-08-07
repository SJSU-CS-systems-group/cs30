package backend

import auth.ApiToken
import auth.KioskSecretDesktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
                authHeader?.let { setRequestProperty("Authorization", it) }
                KioskSecretDesktop.value?.let { setRequestProperty(KioskSecretDesktop.headerName, it) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code
        }.getOrDefault(-1)
    }

actual suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String =
    withContext(Dispatchers.IO) {
        val conn = (URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            authHeader?.let { setRequestProperty("Authorization", it) }
            KioskSecretDesktop.value?.let { setRequestProperty(KioskSecretDesktop.headerName, it) }
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
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            authHeader?.let { setRequestProperty("Authorization", it) }
            KioskSecretDesktop.value?.let { setRequestProperty(KioskSecretDesktop.headerName, it) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        text
    }

actual fun getCurrentAuthHeader(): String? = ApiToken.value?.let { "Bearer $it" }

actual suspend fun deleteWithAuth(url: String, authHeader: String?): Int =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 5000
                readTimeout = 10_000
                authHeader?.let { setRequestProperty("Authorization", it) }
                KioskSecretDesktop.value?.let { setRequestProperty(KioskSecretDesktop.headerName, it) }
            }
            val code = conn.responseCode
            conn.disconnect()
            code
        }.getOrDefault(-1)
    }
