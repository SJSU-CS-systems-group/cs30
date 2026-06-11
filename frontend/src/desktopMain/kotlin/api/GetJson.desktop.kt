package backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun getJson(baseUrl: String, path: String, authHeader: String?): String = withContext(Dispatchers.IO) {
    val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
    if (authHeader != null) {
        connection.setRequestProperty("Authorization", authHeader)
    }

    val statusCode = connection.responseCode
    when {
        statusCode == 404 -> throw NoSuchElementException("NOT_ENROLLED")
        statusCode !in 200..299 -> throw IOException("HTTP $statusCode")
        else -> {
            val input = connection.inputStream
            return@withContext input.bufferedReader().readText()
        }
    }
}
