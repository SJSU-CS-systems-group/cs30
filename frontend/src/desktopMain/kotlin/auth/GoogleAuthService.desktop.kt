package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import data.AuthResult
import data.Student
import java.awt.Desktop
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

object GoogleAuthService : AuthService {

    private var _currentUser: Student? = null

    override suspend fun login(): AuthResult = withContext(Dispatchers.IO) {
        if (!isBackendReachable()) {
            return@withContext AuthResult(
                success = false,
                student = null,
                errorMessage = "Cannot reach login server at ${AuthConfigDesktop.BACKEND_LOGIN_URL}"
            )
        }

        val state = generateState()
        val serverSocket = openCallbackServer()
        openBrowser(serverSocket.localPort, state)

        return@withContext try {
            val params = awaitCallback(serverSocket)
            validateCallback(params, state)
        } catch (e: SocketTimeoutException) {
            AuthResult(success = false, student = null, errorMessage = "Login timed out. Please try again.")
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    override suspend fun logout() {
        _currentUser = null
    }

    override fun currentUser(): Student? = _currentUser

    private fun isBackendReachable(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(
                AuthConfigDesktop.backendInetAddress(),
                AuthConfigDesktop.BACKEND_CHECK_TIMEOUT_MS
            )
            socket.close()
            true
        } catch (e: ConnectException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun generateState(): String = UUID.randomUUID().toString()

    private fun openCallbackServer(): ServerSocket {
        val socket = ServerSocket(0)
        socket.soTimeout = AuthConfigDesktop.CALLBACK_TIMEOUT_MS
        return socket
    }

    private fun openBrowser(port: Int, state: String) {
        val callbackUrl = "http://localhost:$port"
        val encodedCallback = callbackUrl.encodeURLParameter()
        val encodedState = state.encodeURLParameter()
        val loginUrl = "${AuthConfigDesktop.BACKEND_LOGIN_URL}?app_callback=$encodedCallback&state=$encodedState"
        Desktop.getDesktop().browse(URI(loginUrl))
    }

    private fun awaitCallback(serverSocket: ServerSocket): Map<String, String> {
        val client = serverSocket.accept()
        val requestLine = client.getInputStream().bufferedReader().readLine() ?: ""

        val html = "<html><head><script>setTimeout(() => window.close(), 500)</script></head><body><h2>Login complete</h2><p>Returning to CS30…</p></body></html>"
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html"
        client.getOutputStream().write(response.toByteArray())
        client.getOutputStream().flush()
        client.close()

        return parseQueryParams(requestLine)
    }

    private fun parseQueryParams(requestLine: String): Map<String, String> {
        val queryString = requestLine
            .removePrefix("GET ")
            .substringAfter("?", missingDelimiterValue = "")
            .substringBefore(" ")

        return queryString.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()
    }

    private fun validateCallback(params: Map<String, String>, expectedState: String): AuthResult {
        val actualState = params["state"]
        if (actualState != expectedState) {
            return AuthResult(success = false, student = null, errorMessage = "Invalid login session. Please try again.")
        }

        val email = params["email"]?.trim().takeIf { !it.isNullOrBlank() }
            ?: return AuthResult(success = false, student = null, errorMessage = "Login failed: missing email")

        val name = params["name"]?.trim().takeIf { !it.isNullOrBlank() }
            ?: return AuthResult(success = false, student = null, errorMessage = "Login failed: missing name")

        if (AuthConfigDesktop.ALLOWED_EMAIL_DOMAIN.isNotEmpty() && !email.endsWith(AuthConfigDesktop.ALLOWED_EMAIL_DOMAIN)) {
            return AuthResult(
                success = false,
                student = null,
                errorMessage = "Access restricted to ${AuthConfigDesktop.ALLOWED_EMAIL_DOMAIN} accounts"
            )
        }

        val apiToken = params["api_token"]?.trim()
        if (!apiToken.isNullOrBlank()) ApiToken.value = apiToken

        val student = Student(id = email, name = name, email = email)
        _currentUser = student
        return AuthResult(success = true, student = student)
    }

    private fun String.encodeURLParameter(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}

actual fun createAuthService(): AuthService = GoogleAuthService
