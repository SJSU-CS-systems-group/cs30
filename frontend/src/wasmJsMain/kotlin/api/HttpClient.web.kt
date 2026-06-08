package backend

actual suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?) {
    fetchPost("$baseUrl$path", body)
}

private fun fetchPost(url: String, body: String): Unit =
    js("{ try { fetch(url, { method:'POST', headers:{'Content-Type':'application/json'}, body:body, credentials:'same-origin' }); } catch(e){} }")

// Web uses session cookies for auth, so no Bearer token is needed
actual fun getCurrentAuthHeader(): String? = null
