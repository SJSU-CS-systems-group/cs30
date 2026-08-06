package com.cs30.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Verifies the Spring wiring, which KioskGateFilterTest deliberately does not touch: that every
 * `@Value` placeholder in [WebConfig] resolves, that the documented defaults are the ones actually
 * applied, and that the resulting filter is registered after the IP filter.
 *
 * Uses ApplicationContextRunner rather than @SpringBootTest so no database or servlet container is
 * needed — a misspelled placeholder or an unparseable default would otherwise only surface when the
 * real app tried to boot.
 */
class WebConfigKioskWiringTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(WebConfig::class.java)

    @Test
    fun `context starts and the gate is off when no kiosk properties are set`() {
        runner.run { context ->
            assertNull(context.startupFailure, "all @Value placeholders must resolve")

            val chain = MockFilterChain()
            kioskFilter(context).doFilter(
                MockHttpServletRequest("GET", "/api/code/run"),
                MockHttpServletResponse(),
                chain
            )
            assertNotNull(chain.request, "an unset cs30.kiosk-secret must disable the gate")
        }
    }

    @Test
    fun `default exempt paths resolve so the app is gated but health and login are not`() {
        runner.withPropertyValues("cs30.kiosk-secret=$SECRET").run { context ->
            assertNull(context.startupFailure)
            val filter = kioskFilter(context)

            val gated = MockFilterChain()
            filter.doFilter(MockHttpServletRequest("GET", "/"), MockHttpServletResponse(), gated)
            assertNull(gated.request, "the app root must be gated once a secret is configured")

            // These two defaults are load-bearing: /health keeps the CI deploy gate green, and
            // /login keeps desktop OAuth working from a browser that has no cookie or header.
            listOf("/health", "/login", "/callback", "/ta", "/api/ta/sessions").forEach { path ->
                val chain = MockFilterChain()
                filter.doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), chain)
                assertNotNull(chain.request, "$path must be exempt by default")
            }
        }
    }

    @Test
    fun `default header and param names resolve`() {
        runner.withPropertyValues("cs30.kiosk-secret=$SECRET").run { context ->
            val filter = kioskFilter(context)

            val viaHeader = MockFilterChain()
            filter.doFilter(
                MockHttpServletRequest("POST", "/api/code/run").apply {
                    addHeader("X-CS30-Kiosk", SECRET)
                },
                MockHttpServletResponse(), viaHeader
            )
            assertNotNull(viaHeader.request, "default header name must attest")

            val handshake = MockHttpServletResponse()
            filter.doFilter(
                MockHttpServletRequest("GET", "/").apply {
                    setParameter("kiosk", SECRET)
                    queryString = "kiosk=$SECRET"
                },
                handshake, MockFilterChain()
            )
            assertNotNull(handshake.getCookie("cs30_kiosk"), "default param and cookie names must match")
        }
    }

    @Test
    fun `the kiosk gate runs immediately after the ip whitelist`() {
        runner.run { context ->
            val ipRegistration = registration(context, "ipWhitelistFilter")
            val kioskRegistration = registration(context, "kioskGateFilter")

            assertEquals(Ordered.HIGHEST_PRECEDENCE, ipRegistration.order)
            assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, kioskRegistration.order)
            assertEquals(setOf("/*"), kioskRegistration.urlPatterns)
        }
    }

    @Test
    fun `overriding a property replaces the default`() {
        runner.withPropertyValues(
            "cs30.kiosk-secret=$SECRET",
            "cs30.kiosk.header-name=X-Lab-Pass",
            "cs30.kiosk.exempt-paths=/health"
        ).run { context ->
            assertNull(context.startupFailure)
            val filter = kioskFilter(context)

            val custom = MockFilterChain()
            filter.doFilter(
                MockHttpServletRequest("GET", "/api/labs/student").apply {
                    addHeader("X-Lab-Pass", SECRET)
                },
                MockHttpServletResponse(), custom
            )
            assertNotNull(custom.request, "the configured header name must be honoured")

            val noLongerExempt = MockFilterChain()
            filter.doFilter(MockHttpServletRequest("GET", "/login"), MockHttpServletResponse(), noLongerExempt)
            assertNull(noLongerExempt.request, "a narrowed exempt list must drop /login")
        }
    }

    private fun kioskFilter(context: org.springframework.context.ApplicationContext): KioskGateFilter =
        registration(context, "kioskGateFilter").filter as KioskGateFilter

    @Suppress("UNCHECKED_CAST")
    private fun registration(
        context: org.springframework.context.ApplicationContext,
        beanName: String
    ): FilterRegistrationBean<*> =
        context.getBean(beanName) as FilterRegistrationBean<*>

    private companion object {
        const val SECRET = "mock-api-key"
    }
}
