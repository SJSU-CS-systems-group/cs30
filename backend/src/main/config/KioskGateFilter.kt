package com.cs30.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Everything the kiosk gate needs, resolved from configuration by [WebConfig].
 *
 * The filter holds no policy literals of its own so an operator can retune paths, names and the
 * cookie lifetime without a code change, and so tests can exercise it against a plain object.
 */
data class KioskGateSettings(
    val secret: String,
    val exemptPaths: List<String>,
    val cookieName: String,
    val headerName: String,
    val paramName: String,
    val cookieMaxAgeSeconds: Int,
    val blockedMessage: String
)

/**
 * Requires lab-kiosk attestation before a request reaches any controller or static asset.
 *
 * Lab workstations run CS30 through a dedicated kiosk account. Without this gate a student can log
 * into the same workstation under their own account, open a browser, and reach the app — escaping
 * the kiosk environment and its lockdown enforcement. [IpWhitelistFilter] cannot catch that: it
 * sees the network, not which OS account made the request, and both accounts share the machine's IP.
 *
 * Two carriers:
 *  - the configured header — the desktop app (Linux lab), which reads the secret from its process
 *    environment; see KioskSecret.desktop.kt
 *  - the configured query param on a GET — the one-shot launcher handshake (Windows lab), answered
 *    with a 302 that strips the param and sets a cookie the browser then sends on every request
 *
 * An empty secret disables the gate entirely, the same "empty means off" idiom `cs30.allowed-ips`
 * uses in [IpWhitelistFilter].
 *
 * This is an environment attestation, NOT identity: it answers "did this come from a lab kiosk?",
 * never "which student is this?". Identity still comes only from the Bearer token via
 * StudentIdentityService, so the layers are independent — a valid token without attestation gets
 * 403, and valid attestation without a token gets 401.
 *
 * `/login` is exempt by default and must stay that way: the desktop app opens Google OAuth in a
 * *separate* system browser that holds no cookie and cannot send a header. Gating `/login` would
 * break desktop login, and the obvious workaround (appending the secret to the login URL) would
 * hand that browser a cookie and with it full web-app access. Every backend URL that browser
 * visits — `/login` and `/callback` — is exempt, and its final hop is the desktop app's own
 * localhost socket, so it never needs attestation and is never given any.
 */
class KioskGateFilter(private val settings: KioskGateSettings) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        if (settings.secret.isEmpty() || !shouldGate(request.requestURI)) {
            chain.doFilter(request, response)
            return
        }
        if (matchesSecret(request.getHeader(settings.headerName))) {
            chain.doFilter(request, response)
            return
        }
        // GET only: a non-GET cannot be safely redirected, and limiting it this way makes it
        // impossible for getParameter() to consume a JSON request body. Desktop uses the header.
        if (request.method == HANDSHAKE_METHOD) {
            val presentedInUrl = request.getParameter(settings.paramName)
            if (presentedInUrl != null) {
                if (matchesSecret(presentedInUrl)) grantAndRedirect(request, response)
                else reject(request, response, "invalid secret presented")
                return
            }
        }
        if (matchesSecret(kioskCookieValue(request))) {
            chain.doFilter(request, response)
            return
        }
        reject(request, response, "no attestation")
    }

    private fun kioskCookieValue(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == settings.cookieName }?.value

    private fun matchesSecret(presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            settings.secret.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Returns true only for student-facing API paths that are not TA/admin-exempt.
     * Non-`/api/` paths (static assets, SPA routes, OAuth) pass unconditionally — they carry
     * no student data, so gating them would only add friction without adding protection.
     * Within `/api/`, entries ending in `/` are prefix-matched; others require exact or
     * whole-segment match.
     */
    private fun shouldGate(path: String): Boolean {
        if (!path.startsWith(API_PREFIX)) return false
        return settings.exemptPaths.none { entry ->
            if (entry.endsWith(PATH_SEPARATOR)) path.startsWith(entry)
            else path == entry || path.startsWith(entry + PATH_SEPARATOR)
        }
    }

    /**
     * Accepts the launcher handshake: sets the attestation cookie, then redirects to the same path
     * without the secret so it does not linger in the URL bar, history or Referer.
     */
    private fun grantAndRedirect(request: HttpServletRequest, response: HttpServletResponse) {
        response.addCookie(
            Cookie(settings.cookieName, settings.secret).apply {
                path = PATH_SEPARATOR
                isHttpOnly = true
                // Mirrors the request rather than hardcoding true: production is HTTPS, but local
                // dev is plain HTTP, where a Secure cookie is accepted and then never sent back —
                // which presents as a permanent and baffling 403.
                secure = request.isSecure
                maxAge = settings.cookieMaxAgeSeconds
                setAttribute(SAME_SITE_ATTRIBUTE, SAME_SITE_LAX)
            }
        )
        response.setHeader(CACHE_CONTROL_HEADER, NO_STORE)
        response.sendRedirect(targetWithoutHandshakeParam(request))
    }

    /**
     * Strips only the handshake param and keeps the rest of the query string.
     *
     * No redirect loop is possible: the target carries no handshake param, so a cookie that failed
     * to stick yields a clean 403 on the next request rather than bouncing forever.
     */
    private fun targetWithoutHandshakeParam(request: HttpServletRequest): String {
        val remaining = request.queryString
            ?.split(QUERY_SEPARATOR)
            ?.filter { it.isNotEmpty() && it != settings.paramName && !it.startsWith(settings.paramName + "=") }
            ?.joinToString(QUERY_SEPARATOR)
            .orEmpty()
        return if (remaining.isEmpty()) request.requestURI else "${request.requestURI}?$remaining"
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, reason: String) {
        // Never log the expected or presented secret — method, path and IP only.
        log.warn(
            "[kiosk] blocked {} {} from {} ({})",
            request.method, request.requestURI, request.remoteAddr, reason
        )
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.setHeader(CACHE_CONTROL_HEADER, NO_STORE)
        if (wantsHtml(request)) {
            response.contentType = HTML_CONTENT_TYPE
            response.writer.write(blockedPage(settings.blockedMessage))
        } else {
            // Plain text, not JSON: the raw-JS heartbeat in index.html calls res.json(), which
            // throws on this body and lands in its existing .catch, so a blocked API call is logged
            // rather than mistaken for an expired session.
            response.contentType = TEXT_CONTENT_TYPE
            response.writer.write(REJECT_BODY)
        }
    }

    private fun wantsHtml(request: HttpServletRequest): Boolean =
        request.getHeader(ACCEPT_HEADER)?.contains(HTML_MIME) == true

    companion object {
        private val log = LoggerFactory.getLogger(KioskGateFilter::class.java)

        const val REJECT_BODY = "kiosk_required"

        private const val HANDSHAKE_METHOD = "GET"
        private const val API_PREFIX = "/api/"
        private const val PATH_SEPARATOR = "/"
        private const val QUERY_SEPARATOR = "&"
        private const val SAME_SITE_ATTRIBUTE = "SameSite"
        private const val SAME_SITE_LAX = "Lax"
        private const val ACCEPT_HEADER = "Accept"
        private const val CACHE_CONTROL_HEADER = "Cache-Control"
        private const val NO_STORE = "no-store"
        private const val HTML_MIME = "text/html"
        private const val HTML_CONTENT_TYPE = "text/html;charset=UTF-8"
        private const val TEXT_CONTENT_TYPE = "text/plain;charset=UTF-8"

        private fun blockedPage(message: String) = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Launch Required — CS30</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  background: #1e1e1e; color: #d4d4d4;
                  display: flex; align-items: center; justify-content: center;
                  min-height: 100vh;
                }
                .card {
                  background: #252526; border: 1px solid #3c3c3c;
                  border-radius: 8px; padding: 40px 48px; max-width: 480px; width: 90%;
                  text-align: center;
                }
                .icon { font-size: 48px; margin-bottom: 20px; }
                h1 { font-size: 20px; font-weight: 600; color: #ffffff; margin-bottom: 12px; }
                p { font-size: 14px; line-height: 1.6; color: #9d9d9d; margin-bottom: 8px; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">🖥️</div>
                <h1>Launch CS30 from the Lab Desktop</h1>
                <p>$message</p>
                <p>Contact your instructor if you believe this is an error.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
