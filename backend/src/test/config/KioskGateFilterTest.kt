package com.cs30.server.config

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Exercises the filter directly against MockHttpServletRequest/Response rather than through MockMvc.
 *
 * HealthControllerTest — the only MockMvc test in the repo — uses @AutoConfigureMockMvc(addFilters =
 * false), which would silently disable the very thing under test here. A filter is a pure function
 * of request to response, so plain mocks are both sufficient and less machinery.
 */
class KioskGateFilterTest {

    // --- feature toggle -------------------------------------------------------------------------

    @Test
    fun `empty secret allows every request and sets no cookie`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter(secret = "").doFilter(get("/api/code/run"), response, chain)

        assertNotNull(chain.request, "chain should be invoked when the gate is disabled")
        assertNull(response.getCookie(COOKIE_NAME))
        assertEquals(HttpServletResponse.SC_OK, response.status)
    }

    // --- launcher handshake ---------------------------------------------------------------------

    @Test
    fun `valid handshake param sets the cookie and redirects without it`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter().doFilter(handshake("/", SECRET), response, chain)

        assertNull(chain.request, "the handshake must redirect, not fall through to the chain")
        assertEquals(HttpServletResponse.SC_FOUND, response.status)
        assertEquals("/", response.redirectedUrl)
        assertNotNull(response.getCookie(COOKIE_NAME))
        assertEquals(NO_STORE, response.getHeader(CACHE_CONTROL))
    }

    @Test
    fun `handshake preserves the other query params`() {
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("GET", "/login").apply {
            setParameter(PARAM_NAME, SECRET)
            queryString = "app_callback=http%3A%2F%2Flocalhost%3A9999&$PARAM_NAME=$SECRET&state=abc"
        }

        filter(exemptPaths = listOf("/health")).doFilter(request, response, MockFilterChain())

        assertEquals(
            "/login?app_callback=http%3A%2F%2Flocalhost%3A9999&state=abc",
            response.redirectedUrl
        )
    }

    @Test
    fun `cookie is httpOnly, rooted and session scoped`() {
        val response = MockHttpServletResponse()

        filter().doFilter(handshake("/", SECRET), response, MockFilterChain())

        val cookie = response.getCookie(COOKIE_NAME)!!
        assertEquals(SECRET, cookie.value)
        assertTrue(cookie.isHttpOnly, "must be unreadable from page JavaScript")
        assertEquals("/", cookie.path)
        assertEquals(-1, cookie.maxAge, "session scoped so it dies with the kiosk browser")
        assertEquals("Lax", cookie.getAttribute("SameSite"), "must survive Google's redirect back")
    }

    @Test
    fun `cookie is secure only when the request is secure`() {
        val secureResponse = MockHttpServletResponse()
        filter().doFilter(handshake("/", SECRET).apply { isSecure = true }, secureResponse, MockFilterChain())
        assertTrue(secureResponse.getCookie(COOKIE_NAME)!!.secure)

        // Local dev is plain HTTP. A Secure cookie there is accepted and then never sent back,
        // which presents as a permanent, unexplained 403.
        val plainResponse = MockHttpServletResponse()
        filter().doFilter(handshake("/", SECRET), plainResponse, MockFilterChain())
        assertFalse(plainResponse.getCookie(COOKIE_NAME)!!.secure)
    }

    @Test
    fun `handshake param on a POST does not attest`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        val request = MockHttpServletRequest("POST", "/api/code/run").apply {
            setParameter(PARAM_NAME, SECRET)
        }

        filter().doFilter(request, response, chain)

        assertNull(chain.request)
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
    }

    // --- established attestation ----------------------------------------------------------------

    @Test
    fun `valid cookie is allowed`() {
        val chain = MockFilterChain()
        val request = get("/api/problems/lab").apply { setCookies(Cookie(COOKIE_NAME, SECRET)) }

        filter().doFilter(request, MockHttpServletResponse(), chain)

        assertNotNull(chain.request)
    }

    @Test
    fun `valid header is allowed on a POST and does not redirect`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        val request = MockHttpServletRequest("POST", "/api/code/run").apply {
            addHeader(HEADER_NAME, SECRET)
        }

        filter().doFilter(request, response, chain)

        assertNotNull(chain.request)
        assertNull(response.redirectedUrl)
    }

    @Test
    fun `a POST body survives the filter`() {
        val chain = MockFilterChain()
        val body = """{"code":"print(1)"}"""
        val request = MockHttpServletRequest("POST", "/api/code/run").apply {
            contentType = "application/json"
            characterEncoding = "UTF-8"
            setContent(body.toByteArray())
            setCookies(Cookie(COOKIE_NAME, SECRET))
        }

        filter().doFilter(request, MockHttpServletResponse(), chain)

        assertNotNull(chain.request)
        assertEquals(body, request.contentAsString, "the filter must not consume the request body")
    }

    // --- rejection ------------------------------------------------------------------------------

    @Test
    fun `wrong secret is rejected`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter().doFilter(handshake("/", "not-the-secret"), response, chain)

        assertNull(chain.request)
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
    }

    @Test
    fun `a navigation is rejected with the configured message and never the secret`() {
        val response = MockHttpServletResponse()
        val request = get("/").apply { addHeader("Accept", "text/html,application/xhtml+xml") }

        filter().doFilter(request, response, MockFilterChain())

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
        assertTrue(response.contentType!!.startsWith("text/html"))
        assertTrue(response.contentAsString.contains(BLOCKED_MESSAGE))
        assertFalse(response.contentAsString.contains(SECRET), "the page must never echo the secret")
        assertEquals(NO_STORE, response.getHeader(CACHE_CONTROL))
    }

    @Test
    fun `an api call is rejected as plain text so the heartbeat can ignore it`() {
        val response = MockHttpServletResponse()

        filter().doFilter(get("/api/code/queue-status"), response, MockFilterChain())

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
        assertTrue(response.contentType!!.startsWith("text/plain"))
        assertEquals(KioskGateFilter.REJECT_BODY, response.contentAsString)
    }

    // --- exempt paths ---------------------------------------------------------------------------

    @Test
    fun `default exempt paths pass with no attestation`() {
        // /health keeps the CI deploy gate working; /login and /callback keep desktop OAuth working
        // (the desktop app's browser holds neither cookie nor header); /ta* keeps the TA dashboard
        // out of scope for this feature.
        listOf(
            "/health",
            "/login",
            "/callback",
            "/favicon.ico",
            "/ta",
            "/ta/login",
            "/api/ta/sessions"
        ).forEach { path ->
            val chain = MockFilterChain()
            filter().doFilter(get(path), MockHttpServletResponse(), chain)
            assertNotNull(chain.request, "$path should be exempt")
        }
    }

    @Test
    fun `a sibling path is not exempted by a segment entry`() {
        val chain = MockFilterChain()

        filter().doFilter(get("/tabs"), MockHttpServletResponse(), chain)

        assertNull(chain.request, "/ta must not exempt /tabs")
    }

    @Test
    fun `non-TA api paths stay gated`() {
        val chain = MockFilterChain()

        filter().doFilter(get("/api/courses"), MockHttpServletResponse(), chain)

        assertNull(chain.request)
    }

    // --- configuration --------------------------------------------------------------------------

    @Test
    fun `configured cookie, header, param and exempt names are all honoured`() {
        val custom = filter(
            exemptPaths = listOf("/ping"),
            cookieName = "custom_cookie",
            headerName = "X-Custom-Kiosk",
            paramName = "pass"
        )

        val headerChain = MockFilterChain()
        custom.doFilter(
            get("/api/code/run").apply { addHeader("X-Custom-Kiosk", SECRET) },
            MockHttpServletResponse(), headerChain
        )
        assertNotNull(headerChain.request, "configured header name should attest")

        val cookieChain = MockFilterChain()
        custom.doFilter(
            get("/api/code/run").apply { setCookies(Cookie("custom_cookie", SECRET)) },
            MockHttpServletResponse(), cookieChain
        )
        assertNotNull(cookieChain.request, "configured cookie name should attest")

        val paramResponse = MockHttpServletResponse()
        custom.doFilter(
            MockHttpServletRequest("GET", "/").apply {
                setParameter("pass", SECRET)
                queryString = "pass=$SECRET"
            },
            paramResponse, MockFilterChain()
        )
        assertEquals("custom_cookie", paramResponse.getCookie("custom_cookie")?.name)

        val exemptChain = MockFilterChain()
        custom.doFilter(get("/ping"), MockHttpServletResponse(), exemptChain)
        assertNotNull(exemptChain.request, "configured exempt path should pass")

        val noLongerExempt = MockFilterChain()
        custom.doFilter(get("/ta"), MockHttpServletResponse(), noLongerExempt)
        assertNull(noLongerExempt.request, "/ta is only exempt because it is in the default list")
    }

    @Test
    fun `configured cookie max age is applied`() {
        val response = MockHttpServletResponse()

        filter(cookieMaxAgeSeconds = 43_200).doFilter(handshake("/", SECRET), response, MockFilterChain())

        assertEquals(43_200, response.getCookie(COOKIE_NAME)!!.maxAge)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun filter(
        secret: String = SECRET,
        exemptPaths: List<String> = DEFAULT_EXEMPT_PATHS,
        cookieName: String = COOKIE_NAME,
        headerName: String = HEADER_NAME,
        paramName: String = PARAM_NAME,
        cookieMaxAgeSeconds: Int = -1,
        blockedMessage: String = BLOCKED_MESSAGE
    ) = KioskGateFilter(
        KioskGateSettings(
            secret = secret,
            exemptPaths = exemptPaths,
            cookieName = cookieName,
            headerName = headerName,
            paramName = paramName,
            cookieMaxAgeSeconds = cookieMaxAgeSeconds,
            blockedMessage = blockedMessage
        )
    )

    private fun get(path: String) = MockHttpServletRequest("GET", path)

    private fun handshake(path: String, secret: String) =
        MockHttpServletRequest("GET", path).apply {
            setParameter(PARAM_NAME, secret)
            queryString = "$PARAM_NAME=$secret"
        }

    private companion object {
        const val SECRET = "mock-api-key"
        const val COOKIE_NAME = "cs30_kiosk"
        const val HEADER_NAME = "X-CS30-Kiosk"
        const val PARAM_NAME = "kiosk"
        const val BLOCKED_MESSAGE = "CS30 must be started using the CS30 shortcut on the lab workstation."
        const val CACHE_CONTROL = "Cache-Control"
        const val NO_STORE = "no-store"

        val DEFAULT_EXEMPT_PATHS =
            listOf("/health", "/login", "/callback", "/favicon.ico", "/ta", "/api/ta/")
    }
}
