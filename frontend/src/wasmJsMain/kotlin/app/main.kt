package app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import auth.ApiToken
import auth.decodeURIComponent
import auth.syncApiTokenToWindow
import data.Student
import data.TaUser
import ta.TaApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val pathname = window.location.pathname
    ComposeViewport(document.getElementById("composeApplication")!!) {
        if (pathname.startsWith("/ta")) {
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

    return TaUser(email = email, name = name)
}
