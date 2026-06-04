package labx.lockdown

actual fun createActivityLogSink(targetDir: String): ActivityLogSink =
    PlatformActivityLogSink("desktop", CompositeActivityLogSink(ConsoleActivityLogSink(), CsvActivityLogSink(targetDir)))
