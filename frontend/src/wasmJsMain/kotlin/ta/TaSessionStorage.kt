package ta

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Persists the TA's session across a browser refresh (the token otherwise only lives in memory
// and a URL param that gets stripped right after login - see main.kt). Restoring from here is
// optimistic; TaDashboard's heartbeat validates it against the server immediately on mount and
// bounces back to login if it's stale.
@Serializable
internal data class StoredTaSession(val token: String, val name: String, val email: String)

private const val TA_SESSION_KEY = "cs30_ta_session"
private val json = Json { ignoreUnknownKeys = true }

private fun setLocalStorageItem(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun getLocalStorageItem(key: String): String? = js("localStorage.getItem(key)")
private fun removeLocalStorageItem(key: String): Unit = js("localStorage.removeItem(key)")

internal fun saveTaSessionToStorage(token: String, name: String, email: String) {
    setLocalStorageItem(TA_SESSION_KEY, json.encodeToString(StoredTaSession(token, name, email)))
}

internal fun loadTaSessionFromStorage(): StoredTaSession? =
    getLocalStorageItem(TA_SESSION_KEY)?.let { raw ->
        runCatching { json.decodeFromString<StoredTaSession>(raw) }.getOrNull()
    }

internal fun clearTaSessionFromStorage() = removeLocalStorageItem(TA_SESSION_KEY)