package labx.lockdown

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant

private val CSV_HEADER = "session_id,timestamp_ms,timestamp_iso,event_kind,detail"

/**
 * Appends lockdown events to activity_log.csv in [targetDir] using a single
 * background IO coroutine. Non-blocking on the caller; ordered writes guaranteed.
 */
class CsvActivityLogSink(targetDir: String) : ActivityLogSink {
    private val file = File(targetDir).also { it.mkdirs() }.resolve("activity_log.csv")
    private val channel = Channel<ActivityLogEntry>(Channel.UNLIMITED)

    init {
        if (!file.exists() || file.length() == 0L) {
            file.writeText("$CSV_HEADER\n")
        }
    }

    private val drainJob = CoroutineScope(Dispatchers.IO).launch {
        OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).use { writer ->
            for (entry in channel) {
                val iso = Instant.ofEpochMilli(entry.timestampMs).toString()
                val safeDetail = entry.detail?.replace("\"", "\"\"") ?: ""
                writer.write("\"${entry.sessionId}\",${entry.timestampMs},${iso},${entry.kind},\"${safeDetail}\"")
                writer.write("\n")
                writer.flush()
            }
        }
    }

    override fun submit(entry: ActivityLogEntry) {
        channel.trySend(entry)
    }

    override suspend fun close() {
        channel.close()
        drainJob.join()
    }
}
