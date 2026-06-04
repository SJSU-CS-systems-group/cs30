package labx.lockdown

/**
 * Web target: POST each entry to the backend /lockdown/activity endpoint.
 * Uses the existing postJson bridge from LockdownReporter.
 * TODO(real-backend): wire to a real endpoint; for now logs to console only.
 */
class HttpActivityLogSink : ActivityLogSink {
    override fun submit(entry: ActivityLogEntry) {
        // TODO(real-backend): replace with postJson(baseUrl, "/lockdown/activity", entry.toJson())
        println("[ActivityLog:web] session=${entry.sessionId} kind=${entry.kind} t=${entry.timestampMs}")
    }
    override suspend fun close() = Unit
}
