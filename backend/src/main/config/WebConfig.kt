package com.cs30.server.config

import org.springframework.context.annotation.Configuration
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

    companion object {
        private const val STATIC_LOCATION = "classpath:/static/"
        private val INDEX_FALLBACK = ClassPathResource("static/index.html")
    }
}
