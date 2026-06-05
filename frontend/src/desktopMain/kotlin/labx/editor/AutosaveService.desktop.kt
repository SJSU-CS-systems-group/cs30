package labx.editor

import labx.auth.ApiToken

actual fun createAutosaveService(
    baseUrl: String,
    problemSlug: String,
): AutosaveService = HttpAutosaveService(baseUrl, ApiToken.value?.let { "Bearer $it" }, problemSlug)
