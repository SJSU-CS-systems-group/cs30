package backend

expect suspend fun getJson(baseUrl: String, path: String, authHeader: String? = null): String
