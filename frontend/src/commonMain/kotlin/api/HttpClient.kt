package backend

/** POST JSON with optional Authorization header. Returns the HTTP status (or -1 on network error). Platform-specific. */
expect suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int

/** POST JSON and return the response body (for both success and handled-error bodies). Platform-specific. */
expect suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String

/** GET JSON and return the response body. Platform-specific. */
expect suspend fun getJsonWithResponse(url: String, authHeader: String?): String

/** A GET's status and, for 2xx only, its body. An error response's body is never read. */
data class HttpGetResult(val status: Int, val body: String?) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * GET JSON and return the status, reading the body only on success - so no server error text can
 * reach the UI. Throws on a network failure. Platform-specific.
 */
expect suspend fun getWithStatus(url: String, authHeader: String?): HttpGetResult

/** Returns the current Bearer auth header if available (e.g., "Bearer <token>"), null otherwise. */
expect fun getCurrentAuthHeader(): String?

/** DELETE request with auth header. Returns HTTP status code. */
expect suspend fun deleteWithAuth(url: String, authHeader: String?): Int
