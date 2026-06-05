package labx.lockdown

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant

private val CSV_HEADER = "session_id,timestamp_ms,timestamp_iso,platform,event_kind,detail"

/**
 * Writes lockdown events for a single session to [fileName] inside [targetDir].
 * Each instance owns one fresh file — no append mode, no existence check.
 * Non-blocking on the caller; writes are ordered via a single IO drain coroutine.
 */
class CsvActivityLogSink(targetDir: String, fileName: String = "activity_log.csv") : ActivityLogSink {
    private val file = File(targetDir).also { it.mkdirs() }.resolve(fileName)
    private val channel = Channel<ActivityLogEntry>(Channel.UNLIMITED)

    private val drainJob = CoroutineScope(Dispatchers.IO).launch {
        OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8).use { writer ->
            writer.write("$CSV_HEADER\n")
            writer.flush()
            for (entry in channel) {
                val iso = Instant.ofEpochMilli(entry.timestampMs).toString()
                val safeDetail = entry.detail?.replace("\"", "\"\"") ?: ""
                writer.write("\"${entry.sessionId}\",${entry.timestampMs},${iso},${entry.platform},${entry.kind},\"${safeDetail}\"")
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
