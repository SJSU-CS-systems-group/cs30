package com.cs30.server.service

import com.cs30.server.models.LoginSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class StudentIdentityService(
    private val tokenStore: ApiTokenStore,
) {
    private val log = LoggerFactory.getLogger(StudentIdentityService::class.java)

    /**
     * Bearer-only. Never trusts request body/params for identity.
     *
     * Also checks (log-only, never blocking) whether this token is being used from the IP it
     * was issued to, via the login_sessions table (one static IP per lab device). login_sessions
     * writes are intentionally fire-and-forget — a DB hiccup must never break login — so this
     * can never be a hard gate: a missing/stale row would otherwise lock out a legitimate
     * student for a reason unrelated to their own request.
     */
    fun resolve(authorizationHeader: String?): String? {
        val token = extractToken(authorizationHeader) ?: return null
        val session = tokenStore.activeSession(token) ?: return null
        checkIpBinding(session)
        return session.studentEmail
    }

    /** Platform ("web"/"desktop") recorded at token issuance — for the activity log CSV column. */
    fun platform(authorizationHeader: String?): String =
        extractToken(authorizationHeader)?.let { tokenStore.platformFor(it) } ?: "unknown"

    /** The auth token identifying the login session. Logged in the activity CSV. */
    fun token(authorizationHeader: String?): String =
        extractToken(authorizationHeader).orEmpty()

    private fun checkIpBinding(session: LoginSession) {
        val remoteAddr = currentRemoteAddr() ?: return
        if (session.ipAddress != remoteAddr) {
            log.warn("[ip-mismatch] token for ${session.studentEmail} used from $remoteAddr, login_sessions recorded ${session.ipAddress}")
        }
    }

    /** Current request's IP without threading HttpServletRequest through every controller. */
    private fun currentRemoteAddr(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr

    private fun extractToken(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
}
