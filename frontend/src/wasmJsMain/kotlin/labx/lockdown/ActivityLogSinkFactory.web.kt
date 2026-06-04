package labx.lockdown

actual fun createActivityLogSink(targetDir: String): ActivityLogSink =
    CompositeActivityLogSink(ConsoleActivityLogSink(), HttpActivityLogSink())
