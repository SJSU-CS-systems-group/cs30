package labx

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import labx.auth.decodeURIComponent
import labx.data.Student

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val initialStudent = parseStudentFromUrl()
    ComposeViewport(document.getElementById("composeApplication")!!) {
        App(initialStudent = initialStudent)
    }
}

private fun parseStudentFromUrl(): Student? {
    val search = window.location.search.trimStart('?')
    if (search.isBlank()) return null
    val params = search.split("&").mapNotNull { param ->
        val parts = param.split("=", limit = 2)
        if (parts.size == 2) parts[0] to decodeURIComponent(parts[1]) else null
    }.toMap()
    val name = params["name"] ?: return null
    val email = params["email"] ?: return null
    window.history.replaceState(null, "", window.location.pathname)
    return Student(id = email, name = name, email = email)
}
