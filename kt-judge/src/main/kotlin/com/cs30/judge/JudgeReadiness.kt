package com.cs30.judge

import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ReadyStatus(val ok: Boolean, val detail: String)

// Readiness probe: is Docker up and the sandbox image present? `docker image
// inspect <image>` answers both in one call (non-zero exit means the daemon is
// unreachable or the image is missing). Cached briefly so frequent polling does
// not shell out on every request.
@Component
class JudgeReadiness(private val props: JudgeProperties) {

    private companion object {
        const val CACHE_MS = 5_000L
        const val CHECK_TIMEOUT_SECONDS = 5L
    }

    private data class Cached(val status: ReadyStatus, val atMs: Long)

    private val cache = AtomicReference<Cached?>(null)

    fun check(nowMs: Long): ReadyStatus {
        val c = cache.get()
        if (c != null && nowMs - c.atMs < CACHE_MS) return c.status
        val status = probe()
        cache.set(Cached(status, nowMs))
        return status
    }

    private fun probe(): ReadyStatus =
        try {
            val proc = ProcessBuilder("docker", "image", "inspect", props.image)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!proc.waitFor(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                ReadyStatus(false, "docker check timed out")
            } else if (proc.exitValue() == 0) {
                ReadyStatus(true, "docker up; image ${props.image} present")
            } else {
                ReadyStatus(false, "docker unavailable or image ${props.image} not found")
            }
        } catch (e: Exception) {
            ReadyStatus(false, "docker not runnable: ${e.message}")
        }
}
