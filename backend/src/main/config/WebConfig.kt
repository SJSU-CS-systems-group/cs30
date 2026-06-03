package com.cs30.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.io.File

@Configuration
class WebConfig(
    @Value("\${webapp.dir:}") private val webAppDirEnv: String
) : WebMvcConfigurer {

    private val webAppDir: File? by lazy {
        val envPath = webAppDirEnv.takeIf { it.isNotBlank() }
        if (envPath != null) {
            File(envPath).absoluteFile
        } else {
            val cwd = File(".").absoluteFile
            val relativePath = "frontend/build/dist/wasmJs/developmentExecutable"
            listOf(
                File(cwd, relativePath),
                File(cwd, "../$relativePath")
            ).firstOrNull { it.exists() }?.canonicalFile
        }
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val dir = webAppDir
        if (dir?.exists() == true) {
            registry.addResourceHandler("/**")
                .addResourceLocations("file:${dir.absolutePath}/")
                .resourceChain(true)
                .addResolver(object : PathResourceResolver() {
                    override fun getResource(resourcePath: String, location: Resource): Resource? {
                        val requested = super.getResource(resourcePath, location)
                        return requested ?: FileSystemResource(File(dir, "index.html"))
                    }
                })
        }
    }
}
