package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import data.AuthResult
import data.Student
import java.awt.Desktop
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

object GoogleAuthService : AuthService {

    private var _currentUser: Student? = null

    private var activeSocket: ServerSocket? = null

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
        activeSocket = serverSocket
        openBrowser(serverSocket.localPort, state)

        return@withContext try {
            coroutineContext.ensureActive()
            val params = awaitCallback(serverSocket)
            coroutineContext.ensureActive()
            validateCallback(params, state)
        } catch (e: SocketTimeoutException) {
            AuthResult(success = false, student = null, errorMessage = "Login timed out. Please try again.")
        } catch (e: java.net.SocketException) {
            // Socket was closed, likely due to cancellation
            AuthResult(success = false, student = null, errorMessage = "Login cancelled.")
        } finally {
            activeSocket = null
            runCatching { serverSocket.close() }
        }
    }

    override fun cancelLogin() {
        runCatching { activeSocket?.close() }
        activeSocket = null
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        val token = ApiToken.value
        if (token != null) {
            runCatching {
                val url = URI("${AuthConfigDesktop.BACKEND_BASE_URL}/api/logout").toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                KioskSecretDesktop.value?.let { conn.setRequestProperty(KioskSecretDesktop.headerName, it) }
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode // trigger the request
                conn.disconnect()
            }
        }
        ApiToken.value = null
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
        // No kiosk secret on this URL, on purpose. /login and /callback are exempt from
        // KioskGateFilter and the final hop is our own localhost socket, so this browser never
        // touches a gated path and needs no attestation. Appending the secret would work, but it
        // would hand the system browser an attestation cookie — letting it load the full web app
        // outside the desktop app's lockdown. Don't "fix" this by adding the param.
        val loginUrl = "${AuthConfigDesktop.BACKEND_LOGIN_URL}?app_callback=$encodedCallback&state=$encodedState"
        openInBrowser(loginUrl)
    }

    /**
     * Opens [url] in the system browser. AWT's [Desktop.browse] relies on
     * libgnome/gvfs integration that is frequently missing on Linux (it then
     * throws "The BROWSE action is not supported on the current platform"), so
     * fall back to the platform's command-line opener.
     */
    private fun openInBrowser(url: String) {
        val uri = URI(url)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri)
                return
            } catch (_: Exception) {
                // fall through to the CLI opener below
            }
        }

        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url) // Linux / *nix
        }
        ProcessBuilder(command).start()
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

        // Check for server-side errors
        val error = params["error"]
        if (error != null) {
            val message = when (error) {
                "session_exists" -> "You already have an active session. Please log out from your other device first."
                "not_enrolled" -> "You are not enrolled in any course. You are not enrolled in any course. Contact your instructor to be enrolled."
                else -> "Login failed: $error"
            }
            return AuthResult(success = false, student = null, errorMessage = message)
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
