package labx.backend

/** POST JSON with optional Authorization header. Fire-and-forget. Platform-specific. */
expect suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?)
