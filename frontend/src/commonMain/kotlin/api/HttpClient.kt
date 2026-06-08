package backend

/** POST JSON with optional Authorization header. Fire-and-forget. Platform-specific. */
expect suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?)

/** Returns the current Bearer auth header if available (e.g., "Bearer <token>"), null otherwise. */
expect fun getCurrentAuthHeader(): String?
