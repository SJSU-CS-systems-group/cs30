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

    override fun onSessionStart(sessionId: String, problemSlug: String): ActivityLogSink =
        HttpActivityLogSink(baseUrl, authHeader, sessionId, problemSlug)

    override suspend fun onSessionEnd(sessionId: String, problemSlug: String) {
        if (baseUrl.isEmpty()) return
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
            if (baseUrl.isEmpty()) continue
            runCatching {
                val violation = LockdownViolation(
                    kind = ViolationKind.valueOf(entry.kind),
                    timestampMs = entry.timestampMs,
                    detail = entry.detail,
                )
                val body = Json.encodeToString(violation)
                postJsonAuth(baseUrl, "/api/activity/$sessionId/$problemSlug/event", body, authHeader)
            }
        }
    }

    override fun submit(entry: ActivityLogEntry) { channel.trySend(entry) }
    override suspend fun close() { channel.close(); drainJob.join() }
}
