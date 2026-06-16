package auth

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import data.AuthResult
import data.Student

@JsName("decodeURIComponent")
external fun decodeURIComponent(value: String): String

object GoogleAuthService : AuthService {

    private var _currentUser: Student? = null

    override suspend fun login(): AuthResult {
        val params = parseQueryString(window.location.search.trimStart('?'))
        val name = params["name"]
        val email = params["email"]
        if (name != null && email != null) {
            window.history.replaceState(null, "", window.location.pathname)
            val student = Student(id = email, name = name, email = email)
            _currentUser = student
            return AuthResult(success = true, student = student)
        }
        // Same-origin login: the backend handles OAuth, sets the JSESSIONID session
        // cookie, then redirects back to "/" with name/email/api_token query params,
        // which the branch above consumes. app_callback is the desktop-only mechanism
        // and is not used on web. A relative URL keeps this on whatever host/scheme the
        // browser is using, so the session cookie round-trips correctly.
        window.location.href = "/login"
        suspendCancellableCoroutine<Nothing> {}
    }

    override suspend fun logout() {
        _currentUser = null
    }

    override fun currentUser(): Student? = _currentUser

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to decodeURIComponent(parts[1]) else null
        }.toMap()
    }
}

actual fun createAuthService(): AuthService = GoogleAuthService
