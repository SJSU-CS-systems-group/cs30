package auth

import java.net.URI

object AuthConfigDesktop {
    val BACKEND_BASE_URL: String = System.getProperty("cs30.backend.url", "http://localhost:8080")
    const val CALLBACK_TIMEOUT_MS = 120_000        // 2 minutes
    const val BACKEND_CHECK_TIMEOUT_MS = 3_000     // 3 seconds
    const val ALLOWED_EMAIL_DOMAIN = "@sjsu.edu"   // set to "" to allow any domain

    val BACKEND_LOGIN_URL get() = "$BACKEND_BASE_URL/login"

    fun backendInetAddress(): java.net.InetSocketAddress {
        val uri = URI(BACKEND_BASE_URL)
        return java.net.InetSocketAddress(uri.host, uri.port)
    }
}
