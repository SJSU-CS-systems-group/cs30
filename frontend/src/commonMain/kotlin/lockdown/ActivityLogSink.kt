package lockdown

import data.LockdownViolation

data class ActivityLogEntry(
    val sessionId: String,
    val timestampMs: Long,
    val kind: String,
    val detail: String?,
    val platform: String = "unknown",
)

fun LockdownViolation.toLogEntry(sessionId: String) =
    ActivityLogEntry(sessionId, timestampMs, kind.name, detail)

interface ActivityLogSink {
    /** Non-blocking. Queues the entry for writing. */
    fun submit(entry: ActivityLogEntry)
    /** Flush all pending entries and release resources. */
    suspend fun close()
}

/** Writes to stdout — active on all targets. */
class ConsoleActivityLogSink : ActivityLogSink {
    override fun submit(entry: ActivityLogEntry) {
        println("[ActivityLog] session=${entry.sessionId} kind=${entry.kind} t=${entry.timestampMs} platform=${entry.platform}${entry.detail?.let { " :: $it" } ?: ""}")
    }
    override suspend fun close() = Unit
}

/** Fans out to multiple sinks. */
class CompositeActivityLogSink(private vararg val sinks: ActivityLogSink) : ActivityLogSink {
    override fun submit(entry: ActivityLogEntry) = sinks.forEach { it.submit(entry) }
    override suspend fun close() = sinks.forEach { it.close() }
}

/** Stamps every entry with the platform name before forwarding. */
class PlatformActivityLogSink(
    private val platform: String,
    private val inner: ActivityLogSink,
) : ActivityLogSink {
    override fun submit(entry: ActivityLogEntry) = inner.submit(entry.copy(platform = platform))
    override suspend fun close() = inner.close()
}
