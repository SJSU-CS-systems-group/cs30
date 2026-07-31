package app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import auth.ApiToken
import auth.decodeURIComponent
import auth.syncApiTokenToWindow
import data.AdminUser
import data.Student
import data.TaUser
import admin.AdminApp
import ta.TaApp
import ta.loadTaSessionFromStorage
import ta.saveTaSessionToStorage

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val pathname = window.location.pathname
    ComposeViewport(document.getElementById("composeApplication")!!) {
        if (pathname.startsWith("/admin")) {
            AdminApp(initialAdmin = parseAdminFromUrl())
        } else if (pathname.startsWith("/ta")) {
            TaApp(initialTa = parseTaFromUrl())
        } else {
            App(initialStudent = parseStudentFromUrl())
        }
    }
}

private fun parseStudentFromUrl(): Student? {
    val search = window.location.search.trimStart('?')
    if (search.isBlank()) return null
    val params = search.split("&").mapNotNull { param ->
        val parts = param.split("=", limit = 2)
        if (parts.size == 2) parts[0] to decodeURIComponent(parts[1].replace("+", "%20")) else null
    }.toMap()
    val name = params["name"] ?: return null
    val email = params["email"] ?: return null
    window.history.replaceState(null, "", window.location.pathname)

    val apiToken = params["api_token"]?.trim()
    if (!apiToken.isNullOrBlank()) {
        ApiToken.value = apiToken
        syncApiTokenToWindow(apiToken)
    }

    return Student(id = email, name = name, email = email)
}

private fun parseTaFromUrl(): TaUser? {
    val search = window.location.search.trimStart('?')
    if (search.isBlank()) return restoreTaFromStorage()
    val params = search.split("&").mapNotNull { param ->
        val parts = param.split("=", limit = 2)
        if (parts.size == 2) parts[0] to decodeURIComponent(parts[1].replace("+", "%20")) else null
    }.toMap()
    val name = params["name"] ?: return restoreTaFromStorage()
    val email = params["email"] ?: return restoreTaFromStorage()
    window.history.replaceState(null, "", window.location.pathname)

    val apiToken = params["api_token"]?.trim()
    if (!apiToken.isNullOrBlank()) {
        ApiToken.value = apiToken
        syncApiTokenToWindow(apiToken)
        saveTaSessionToStorage(apiToken, name, email)
    }

    // Only present right after this TA's own CLI token was just (re)generated - see AdminUser's
    // equivalent field for why it's never persisted to storage or shown again after this load.
    val rawCliToken = params["token"]?.trim()?.takeIf { it.isNotBlank() }

    return TaUser(email = email, name = name, token = rawCliToken)
}

// Falls back to a session saved on a prior login when the URL has no token - e.g. after a browser
// refresh, where the URL param was already stripped and in-memory state is gone. Restoring is
// optimistic: TaDashboard's heartbeat validates it against the server right away and logs out if
// it turns out to be stale or revoked.
private fun restoreTaFromStorage(): TaUser? {
    val stored = loadTaSessionFromStorage() ?: return null
    ApiToken.value = stored.token
    syncApiTokenToWindow(stored.token)
    return TaUser(email = stored.email, name = stored.name)
}

// No localStorage restore, unlike TA/student - a lost admin session just means logging in again,
// which is cheap since there's nothing to re-fetch besides the CLI token table.
private fun parseAdminFromUrl(): AdminUser? {
    val search = window.location.search.trimStart('?')
    if (search.isBlank()) return null
    val params = search.split("&").mapNotNull { param ->
        val parts = param.split("=", limit = 2)
        if (parts.size == 2) parts[0] to decodeURIComponent(parts[1].replace("+", "%20")) else null
    }.toMap()
    val name = params["name"] ?: return null
    val email = params["email"] ?: return null
    val sessionToken = params["session_token"] ?: return null
    window.history.replaceState(null, "", window.location.pathname)

    // The admin page authenticates its own API calls (listing/deleting CLI tokens) with this
    // session token, NOT with the raw CLI token below - that one is only ever displayed for the
    // admin to copy, never used as a bearer credential by the frontend itself.
    ApiToken.value = sessionToken
    syncApiTokenToWindow(sessionToken)

    val rawCliToken = params["token"]?.trim()?.takeIf { it.isNotBlank() }

    return AdminUser(email = email, name = name, sessionToken = sessionToken, token = rawCliToken)
}
