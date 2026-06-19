package com.cs30.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.WebContentInterceptor
import org.springframework.web.servlet.resource.PathResourceResolver
import java.time.Duration

/**
 * Serves the bundled wasmJs web app from inside the jar (classpath:/static).
 *
 * The production frontend distribution is copied into static/ at build time
 * (see backend/build.gradle.kts processResources), so the jar is self-contained:
 * `java -jar` serves the web app with no external files or runtime paths.
 *
 * Controller routes (the API and OAuth endpoints) are matched before this catch-all
 * handler; any unmatched path falls back to index.html so client-side deep links work.
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations(STATIC_LOCATION)
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(resourcePath: String, location: Resource): Resource {
                    val resource = super.getResource(resourcePath, location)
                    return if (resource?.exists() == true) resource else INDEX_FALLBACK
                }
            })
    }

    /**
     * Cache-Control by request path. The wasm/JS/asset bundles are content-hash-named (their
     * filename changes when their bytes change), so they can be cached for a year — repeat
     * loads on the same machine then fetch nothing. index.html is NOT hashed and references
     * the hashed bundles, so it must always be revalidated, or an updated build would never
     * be picked up. Keyed on the request path (not the resolved resource) so the SPA fallback
     * to index.html for an unknown deep link is never cached long either.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            WebContentInterceptor().apply {
                // PathPattern forbids `**` mid-pattern, so match the root-level bundles by
                // single-segment globs (composeApp.js/.wasm, the hashed Skia .wasm, .map) and
                // the nested composeResources tree separately.
                addCacheMapping(
                    CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable(),
                    "/*.wasm", "/*.js", "/*.css", "/*.map", "/composeResources/**"
                )
                addCacheMapping(CacheControl.noCache(), "/", "/index.html")
            }
        )
    }

    companion object {
        private const val STATIC_LOCATION = "classpath:/static/"
        private val INDEX_FALLBACK = ClassPathResource("static/index.html")
    }
}
