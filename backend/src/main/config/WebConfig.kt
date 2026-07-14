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
    @Value("\${cs30.allowed-ips:}") private val allowedIpsRaw: String
) : WebMvcConfigurer {

    @Bean
    fun ipWhitelistFilter(): FilterRegistrationBean<IpWhitelistFilter> {
        val entries = allowedIpsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return FilterRegistrationBean(IpWhitelistFilter(entries)).apply {
            addUrlPatterns("/*")
            order = Ordered.HIGHEST_PRECEDENCE
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
                    // Serve ta.html for /ta routes, index.html for others
                    return if (resourcePath.startsWith("ta")) TA_FALLBACK else INDEX_FALLBACK
                }
            })
    }

    companion object {
        private const val STATIC_LOCATION = "classpath:/static/"
        private val INDEX_FALLBACK = ClassPathResource("static/index.html")
        private val TA_FALLBACK = ClassPathResource("static/ta.html")
    }
}
