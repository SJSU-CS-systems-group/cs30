package labx.lockdown

actual fun createActivityLogSink(targetDir: String): ActivityLogSink =
    PlatformActivityLogSink("web", CompositeActivityLogSink(ConsoleActivityLogSink(), HttpActivityLogSink()))
