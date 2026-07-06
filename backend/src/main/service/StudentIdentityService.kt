package com.cs30.server.service

import com.cs30.server.repository.LoginSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class StudentIdentityService(
    private val tokenStore: ApiTokenStore,
    private val loginSessionRepository: LoginSessionRepository,
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
        val email = tokenStore.resolve(token) ?: return null
        checkIpBinding(email, token)
        return email
    }

    /** Platform ("web"/"desktop") recorded at token issuance — for the activity log CSV column. */
    fun platform(authorizationHeader: String?): String =
        extractToken(authorizationHeader)?.let { tokenStore.platformFor(it) } ?: "unknown"

    /** The auth token identifying the login session. Logged in the activity CSV. */
    fun token(authorizationHeader: String?): String =
        extractToken(authorizationHeader).orEmpty()

    private fun checkIpBinding(email: String, token: String) {
        val remoteAddr = currentRemoteAddr() ?: return
        val loginSession = loginSessionRepository.findById(remoteAddr).orElse(null)
        if (loginSession == null) {
            log.warn("[ip-mismatch] token for $email used from $remoteAddr, no login_sessions row for that IP")
        } else if (loginSession.token != token) {
            log.warn("[ip-mismatch] token for $email used from $remoteAddr, login_sessions has ${loginSession.studentEmail}")
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
