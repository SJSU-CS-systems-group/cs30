package auth

import backend.postJsonAuth
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import data.AuthResult
import data.Student
import kotlin.js.Promise

@JsName("decodeURIComponent")
external fun decodeURIComponent(value: String): String

object GoogleAuthService : AuthService {

    private var _currentUser: Student? = null

    override suspend fun login(): AuthResult {
        val params = parseQueryString(window.location.search.trimStart('?'))

        // Check for server-side errors
        val error = params["error"]
        if (error != null) {
            window.history.replaceState(null, "", window.location.pathname)
            val message = when (error) {
                "session_exists" -> "You already have an active session. Please log out from your other device first."
                "not_enrolled" -> "You are not enrolled in any course. Contact your instructor to be enrolled."
                else -> "Login failed: $error"
            }
            return AuthResult(success = false, student = null, errorMessage = message)
        }

        val name = params["name"]
        val email = params["email"]
        if (name != null && email != null) {
            window.history.replaceState(null, "", window.location.pathname)
            val apiToken = params["api_token"]?.trim()
            if (!apiToken.isNullOrBlank()) {
                ApiToken.value = apiToken
                syncApiTokenToWindow(apiToken)
            }
            val student = Student(id = email, name = name, email = email)
            _currentUser = student
            return AuthResult(success = true, student = student)
        }
        // Same-origin login: the backend handles OAuth, then redirects back to "/" with
        // name/email/api_token query params, which the branch above consumes. Auth from
        // here on is the same Bearer token desktop uses — not a session cookie. app_callback
        // is the desktop-only mechanism and is not used on web. A relative URL keeps this on
        // whatever host/scheme the browser is using.
        window.location.href = "/login"
        suspendCancellableCoroutine<Nothing> {}
    }

    override suspend fun logout() {
        // postJsonAuth already catches its own failures (returns -1) — best-effort revoke,
        // the token is cleared client-side regardless of whether this call succeeds.
        val token = ApiToken.value
        if (token != null) {
            postJsonAuth("", "/api/web-logout", "{}", "Bearer $token")
        }
        ApiToken.value = null
        syncApiTokenToWindow(null)
        _currentUser = null
        // Best-effort: also end the campus Okta session, since Google Workspace SSO for
        // sjsu.edu accounts delegates to Okta — without this, Google's own account picker
        // silently re-authenticates via Okta's still-live session, no password prompt. Fired
        // in the background (no-cors, credentials included) rather than a top-level redirect,
        // since navigating the whole page there and back was what broke logout previously.
        // Safari's ITP blocks third-party cookies on background requests, so this may silently
        // no-op there — a known tradeoff, not a bug, given navigating away isn't an option.
        fireOktaSignout()
        window.location.href = "/"
    }

    override fun currentUser(): Student? = _currentUser

    override fun checkInitialError(): String? {
        val params = parseQueryString(window.location.search.trimStart('?'))
        val error = params["error"]
        if (error != null) {
            window.history.replaceState(null, "", window.location.pathname)
            return when (error) {
                "session_exists" -> "You already have an active session. Please log out from your other device first."
                "not_enrolled" -> "You are not enrolled in any course. You are not enrolled in any course. Contact your instructor to be enrolled."
                else -> "Login failed: $error"
            }
        }
        return null
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to decodeURIComponent(parts[1]) else null
        }.toMap()
    }
}

private fun fireOktaSignout(): Promise<JsAny?> =
    js("fetch('https://sjsu.okta.com/login/signout', { method: 'GET', mode: 'no-cors', credentials: 'include' }).catch(function(e){})")

actual fun createAuthService(): AuthService = GoogleAuthService
