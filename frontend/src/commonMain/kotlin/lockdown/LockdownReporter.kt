package lockdown

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import data.LockdownViolation

/** POST a JSON body to `${baseUrl}${path}`. Platform-specific HTTP. */
expect suspend fun postJson(baseUrl: String, path: String, body: String)

/** Same-origin on web (""); localhost on desktop dev. */
expect val defaultReporterBaseUrl: String

private val json = Json { ignoreUnknownKeys = true }

class LockdownReporter(private val baseUrl: String = defaultReporterBaseUrl) {
    suspend fun observe(controller: LockdownController) {
        controller.violations.collect { v ->
            runCatching { postJson(baseUrl, "/violations", json.encodeToString(v)) }
        }
    }
}
