package labx.lockdown

actual val defaultReporterBaseUrl: String = ""  // same-origin

actual suspend fun postJson(baseUrl: String, path: String, body: String) {
    fetchPost("$baseUrl$path", body)
}

private fun fetchPost(url: String, body: String): Unit =
    js("{ try { fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body, credentials: 'same-origin' }); } catch (err) {} }")
