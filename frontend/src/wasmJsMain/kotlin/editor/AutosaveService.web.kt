package editor

actual fun createAutosaveService(
    baseUrl: String,
    problemSlug: String,
): AutosaveService = HttpAutosaveService(baseUrl, null, problemSlug)
