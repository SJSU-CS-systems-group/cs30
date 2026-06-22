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

    override fun onSessionStart(): ActivityLogSink {
        println("[Activity] onSessionStart baseUrl='$baseUrl'")
        return HttpActivityLogSink(baseUrl, authHeader)
    }

    override suspend fun onSessionEnd() {
        println("[Activity] onSessionEnd commit")
        postJsonAuth(baseUrl, "/api/activity/commit", "", authHeader)
    }
}

class HttpActivityLogSink(
    private val baseUrl: String,
    private val authHeader: String?,
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
                val query = if (entry.problem.isNotBlank()) "?problem=${entry.problem}" else ""
                println("[Activity] event kind=${entry.kind} problem=${entry.problem.ifBlank { "-" }}")
                postJsonAuth(baseUrl, "/api/activity/event$query", body, authHeader)
            }.onFailure { println("[Activity] event POST FAILED: ${it.message}") }
        }
    }

    override fun submit(entry: ActivityLogEntry) { channel.trySend(entry) }
    override suspend fun close() { channel.close(); drainJob.join() }
}
