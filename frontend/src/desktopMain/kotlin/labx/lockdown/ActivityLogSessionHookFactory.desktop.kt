package labx.lockdown

import labx.auth.ApiToken

actual fun createActivityLogSessionHook(baseUrl: String): ActivityLogSessionHook =
    HttpActivityLogSessionHook(baseUrl, ApiToken.value?.let { "Bearer $it" })
