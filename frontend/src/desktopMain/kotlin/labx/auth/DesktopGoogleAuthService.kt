package labx.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import labx.data.AuthResult
import labx.data.Student
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder

object DesktopGoogleAuthService : AuthService {

    private var _currentUser: Student? = null

    override suspend fun login(): AuthResult = withContext(Dispatchers.IO) {
        val port = 9090
        val serverSocket = ServerSocket(port)
        Desktop.getDesktop().browse(URI("http://localhost:8080/login?app_callback=http://localhost:$port"))

        val client = serverSocket.accept()
        val requestLine = client.getInputStream().bufferedReader().readLine() ?: ""

        val html = "<html><body><h2>Login successful!</h2><p>You can close this tab and return to CS30.</p></body></html>"
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html"
        client.getOutputStream().write(response.toByteArray())
        client.getOutputStream().flush()
        client.close()
        serverSocket.close()

        // Parse: GET /?name=John+Doe&email=john@sjsu.edu HTTP/1.1
        val queryString = requestLine.substringAfter("/?").substringBefore(" HTTP")
        val params = queryString.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()

        val name = params["name"] ?: "Unknown"
        val email = params["email"] ?: ""
        val student = Student(id = email, name = name, email = email)
        _currentUser = student
        AuthResult(success = true, student = student)
    }

    override suspend fun logout() {
        _currentUser = null
    }

    override fun currentUser(): Student? = _currentUser
}

actual fun createAuthService(): AuthService = DesktopGoogleAuthService
