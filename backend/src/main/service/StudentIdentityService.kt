package com.cs30.server.service

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component

@Component
class StudentIdentityService(private val tokenStore: ApiTokenStore) {

    /** Web: reads JSESSIONID session. Desktop: validates Bearer token. Never trusts request body. */
    fun resolve(session: HttpSession, authorizationHeader: String?): String? {
        (session.getAttribute("user_email") as? String)?.let { return it }
        val token = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
            ?: return null
        return tokenStore.resolve(token)
    }

    /** Determines client platform from auth type for the activity log CSV column. */
    fun platform(session: HttpSession, authorizationHeader: String?): String =
        if (session.getAttribute("user_email") != null) "web" else "desktop"
}
