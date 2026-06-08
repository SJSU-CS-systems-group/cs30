package backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun getJson(baseUrl: String, path: String, authHeader: String?): String = withContext(Dispatchers.IO) {
    val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
    if (authHeader != null) {
        connection.setRequestProperty("Authorization", authHeader)
    }
    connection.inputStream.bufferedReader().readText()
}
