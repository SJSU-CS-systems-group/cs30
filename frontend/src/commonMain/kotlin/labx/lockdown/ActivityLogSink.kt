package labx.lockdown

import labx.data.LockdownViolation

data class ActivityLogEntry(
    val sessionId: String,
    val timestampMs: Long,
    val kind: String,
    val detail: String?
) {
    fun toCsvRow(): String {
        val safeDetail = detail?.replace("\"", "\"\"") ?: ""
        return "\"$sessionId\",$timestampMs,${kind},\"$safeDetail\""
    }
}

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
        println("[ActivityLog] session=${entry.sessionId} kind=${entry.kind} t=${entry.timestampMs}${entry.detail?.let { " :: $it" } ?: ""}")
    }
    override suspend fun close() = Unit
}

/** Fans out to multiple sinks. */
class CompositeActivityLogSink(private vararg val sinks: ActivityLogSink) : ActivityLogSink {
    override fun submit(entry: ActivityLogEntry) = sinks.forEach { it.submit(entry) }
    override suspend fun close() = sinks.forEach { it.close() }
}
