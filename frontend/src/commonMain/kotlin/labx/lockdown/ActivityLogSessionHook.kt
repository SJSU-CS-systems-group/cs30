package labx.lockdown

interface ActivityLogSessionHook {
    fun onSessionStart(sessionId: String, problemSlug: String): ActivityLogSink
    suspend fun onSessionEnd(sessionId: String, problemSlug: String)
}

object NoOpActivityLogSessionHook : ActivityLogSessionHook {
    override fun onSessionStart(sessionId: String, problemSlug: String): ActivityLogSink = ConsoleActivityLogSink()
    override suspend fun onSessionEnd(sessionId: String, problemSlug: String) = Unit
}
