package labx.auth

object DesktopAuthConfig {
    const val BACKEND_HOST = "localhost"
    const val BACKEND_PORT = 8080
    const val CALLBACK_TIMEOUT_MS = 120_000        // 2 minutes
    const val BACKEND_CHECK_TIMEOUT_MS = 3_000     // 3 seconds
    const val ALLOWED_EMAIL_DOMAIN = "@sjsu.edu"   // set to "" to allow any domain

    val BACKEND_LOGIN_URL get() = "http://$BACKEND_HOST:$BACKEND_PORT/login"
}
