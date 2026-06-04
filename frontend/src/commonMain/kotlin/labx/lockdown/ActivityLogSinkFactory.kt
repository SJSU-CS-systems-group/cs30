package labx.lockdown

/** Returns the platform-appropriate sink wrapped with a console sink. */
expect fun createActivityLogSink(targetDir: String): ActivityLogSink
