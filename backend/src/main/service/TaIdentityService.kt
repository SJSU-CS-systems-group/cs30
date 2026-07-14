package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Identity resolution for TA tokens. Similar to StudentIdentityService but:
 * - Validates that the token platform starts with "ta-"
 * - Returns TA email and can look up associated courses
 * - No IP binding check (TAs are trusted)
 */
@Component
class TaIdentityService(
    private val tokenStore: ApiTokenStore,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(TaIdentityService::class.java)

    /**
     * Resolves a TA's email from the Authorization header.
     * Only accepts tokens with platform starting with "ta-".
     */
    fun resolve(authorizationHeader: String?): String? {
        val token = extractToken(authorizationHeader) ?: return null
        val session = tokenStore.activeSession(token) ?: return null
        if (!session.platform.startsWith("ta-")) {
            log.warn("[ta-identity] rejected non-TA token for ${session.studentEmail}, platform=${session.platform}")
            return null
        }
        return session.studentEmail
    }

    /** The auth token identifying the login session. */
    fun token(authorizationHeader: String?): String =
        extractToken(authorizationHeader).orEmpty()

    /** Get courses where this email is the TA. */
    fun getCoursesForTa(email: String) = courseRepository.findByTaEmail(email)

    private fun extractToken(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
}
