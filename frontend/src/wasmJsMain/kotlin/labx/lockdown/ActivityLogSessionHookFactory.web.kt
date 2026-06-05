package labx.lockdown

actual fun createActivityLogSessionHook(baseUrl: String): ActivityLogSessionHook =
    HttpActivityLogSessionHook(baseUrl, null)
