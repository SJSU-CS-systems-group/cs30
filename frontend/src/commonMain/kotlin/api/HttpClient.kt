package backend

/** POST JSON with optional Authorization header. Returns the HTTP status (or -1 on network error). Platform-specific. */
expect suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int

/** POST JSON and return the response body (for both success and handled-error bodies). Platform-specific. */
expect suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String

/** GET JSON and return the response body. Platform-specific. */
expect suspend fun getJsonWithResponse(url: String, authHeader: String?): String

/** Returns the current Bearer auth header if available (e.g., "Bearer <token>"), null otherwise. */
expect fun getCurrentAuthHeader(): String?

/** DELETE request with auth header. Returns HTTP status code. */
expect suspend fun deleteWithAuth(url: String, authHeader: String?): Int
