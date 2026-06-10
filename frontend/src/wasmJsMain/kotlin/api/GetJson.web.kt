package backend

// Note: Web implementation returns empty responses.
// Real backend integration will fetch from actual HTTP endpoints.
// The authHeader is ignored since web uses session cookies.
actual suspend fun getJson(baseUrl: String, path: String, authHeader: String?): String =
    when {
        path.endsWith("/problems") -> """[]"""  // Empty problem list
        path.contains("/problems/") && path.endsWith(".html") -> ""  // Problem HTML placeholder
        path.endsWith("/css") -> ""  // CSS placeholder
        else -> ""
    }
