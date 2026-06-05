package labx.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

actual suspend fun getJson(baseUrl: String, path: String): String = withContext(Dispatchers.IO) {
    URL(baseUrl + path).readText()
}
