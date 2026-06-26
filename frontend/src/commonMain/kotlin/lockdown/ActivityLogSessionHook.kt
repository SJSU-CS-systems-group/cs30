package lockdown

interface ActivityLogSessionHook {
    fun onSessionStart(): ActivityLogSink
    suspend fun onSessionEnd()
}

object NoOpActivityLogSessionHook : ActivityLogSessionHook {
    override fun onSessionStart(): ActivityLogSink = ConsoleActivityLogSink()
    override suspend fun onSessionEnd() = Unit
}
