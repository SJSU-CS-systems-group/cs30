package com.cs30.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the bundled wasmJs web app from inside the jar (classpath:/static).
 *
 * The production frontend distribution is copied into static/ at build time
 * (see backend/build.gradle.kts processResources), so the jar is self-contained:
 * `java -jar` serves the web app with no external files or runtime paths.
 *
 * Controller routes (the API and OAuth endpoints) are matched before this catch-all
 * handler; any unmatched path falls back to index.html so client-side deep links work.
 *
 * No Cache-Control headers are set here on purpose. An earlier version set per-path
 * headers via WebContentInterceptor.addCacheMapping (exact paths -> no-cache, a wildcard
 * -> immutable/1yr), but that API stores patterns in a plain HashMap with no "most specific
 * wins" rule — confirmed live that /composeApp.js was getting the wildcard's immutable
 * header instead of its intended no-cache one, so browsers pinned a stale loader script
 * after every redeploy until a manual cache clear. Removed rather than patched.
 */
@Configuration
class WebConfig(
    @Value("\${cs30.allowed-ips:}") private val allowedIpsRaw: String,
    @Value("\${cs30.kiosk-secret:}") private val kioskSecret: String,
    @Value("\${cs30.kiosk.exempt-paths:/health,/login,/callback,/favicon.ico,/ta,/api/ta/}")
    private val kioskExemptPathsRaw: String,
    @Value("\${cs30.kiosk.cookie-name:cs30_kiosk}") private val kioskCookieName: String,
    @Value("\${cs30.kiosk.header-name:X-CS30-Kiosk}") private val kioskHeaderName: String,
    @Value("\${cs30.kiosk.param-name:kiosk}") private val kioskParamName: String,
    @Value("\${cs30.kiosk.cookie-max-age-seconds:-1}") private val kioskCookieMaxAgeSeconds: Int,
    @Value("\${cs30.kiosk.blocked-message:CS30 must be started using the CS30 shortcut on the lab workstation.}")
    private val kioskBlockedMessage: String
) : WebMvcConfigurer {

    @Bean
    fun ipWhitelistFilter(): FilterRegistrationBean<IpWhitelistFilter> {
        val entries = allowedIpsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return FilterRegistrationBean(IpWhitelistFilter(entries)).apply {
            addUrlPatterns("/*")
            order = Ordered.HIGHEST_PRECEDENCE
        }
    }

    /**
     * Runs immediately after [ipWhitelistFilter]: the network check is cheaper and coarser, and its
     * "wrong network" page is the more useful message for an off-campus client. Leaving both at
     * HIGHEST_PRECEDENCE would make their relative order undefined.
     *
     * An empty cs30.kiosk-secret disables the gate, matching the cs30.allowed-ips idiom above.
     */
    @Bean
    fun kioskGateFilter(): FilterRegistrationBean<KioskGateFilter> {
        val settings = KioskGateSettings(
            secret = kioskSecret.trim(),
            exemptPaths = kioskExemptPathsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            cookieName = kioskCookieName,
            headerName = kioskHeaderName,
            paramName = kioskParamName,
            cookieMaxAgeSeconds = kioskCookieMaxAgeSeconds,
            blockedMessage = kioskBlockedMessage
        )
        return FilterRegistrationBean(KioskGateFilter(settings)).apply {
            addUrlPatterns("/*")
            order = Ordered.HIGHEST_PRECEDENCE + 1
        }
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations(STATIC_LOCATION)
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(resourcePath: String, location: Resource): Resource {
                    val resource = super.getResource(resourcePath, location)
                    if (resource?.exists() == true) return resource
                    // Serve ta.html for /ta routes, admin.html for /admin routes, index.html for others
                    return when {
                        resourcePath.startsWith("ta") -> TA_FALLBACK
                        resourcePath.startsWith("admin") -> ADMIN_FALLBACK
                        else -> INDEX_FALLBACK
                    }
                }
            })
    }

    companion object {
        private const val STATIC_LOCATION = "classpath:/static/"
        private val INDEX_FALLBACK = ClassPathResource("static/index.html")
        private val TA_FALLBACK = ClassPathResource("static/ta.html")
        private val ADMIN_FALLBACK = ClassPathResource("static/admin.html")
    }
}
