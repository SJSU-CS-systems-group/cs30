package lockdown

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import backend.postJsonAuth
import data.LockdownViolation
import data.ViolationKind

class HttpActivityLogSessionHook(
    private val baseUrl: String,
    private val authHeader: String?,
) : ActivityLogSessionHook {

    override fun onSessionStart(sessionId: String, problemSlug: String): ActivityLogSink {
        println("[Activity] onSessionStart sid=$sessionId slug=$problemSlug baseUrl='$baseUrl'")
        return HttpActivityLogSink(baseUrl, authHeader, sessionId, problemSlug)
    }

    override suspend fun onSessionEnd(sessionId: String, problemSlug: String) {
        println("[Activity] onSessionEnd commit sid=$sessionId slug=$problemSlug")
        postJsonAuth(baseUrl, "/api/activity/$sessionId/$problemSlug/commit", "", authHeader)
    }
}

class HttpActivityLogSink(
    private val baseUrl: String,
    private val authHeader: String?,
    private val sessionId: String,
    private val problemSlug: String,
) : ActivityLogSink {
    private val channel = Channel<ActivityLogEntry>(Channel.UNLIMITED)

    private val drainJob = CoroutineScope(Dispatchers.Default).launch {
        for (entry in channel) {
            runCatching {
                val violation = LockdownViolation(
                    kind = ViolationKind.valueOf(entry.kind),
                    timestampMs = entry.timestampMs,
                    detail = entry.detail,
                )
                val body = Json.encodeToString(violation)
                println("[Activity] event sid=$sessionId slug=$problemSlug kind=${entry.kind}")
                postJsonAuth(baseUrl, "/api/activity/$sessionId/$problemSlug/event", body, authHeader)
            }.onFailure { println("[Activity] event POST FAILED: ${it.message}") }
        }
    }

    override fun submit(entry: ActivityLogEntry) { channel.trySend(entry) }
    override suspend fun close() { channel.close(); drainJob.join() }
}
