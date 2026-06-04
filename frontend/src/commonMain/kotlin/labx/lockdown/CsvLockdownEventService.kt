package labx.lockdown

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import labx.data.LockdownViolation

/**
 * Wraps DummyLockdownEventService (composition, not subclass — it's final) and
 * fans each event to an ActivityLogSink in a parallel coroutine.
 *
 * Session ID resets each time lockdown becomes active, so every exam window gets
 * a distinct ID even within the same process run.
 */
class CsvLockdownEventService(private val sink: ActivityLogSink) : LockdownEventService {
    private val inner = DummyLockdownEventService()
    private val sessionId = MutableStateFlow("")

    override suspend fun observe(controller: LockdownController) {
        coroutineScope {
            // Existing counter/heartbeat/summary logic — unchanged.
            launch { inner.observe(controller) }

            // Track session boundaries.
            launch {
                var wasActive = false
                controller.active.collect { active ->
                    if (active && !wasActive) sessionId.value = "session-${currentEpochMs()}"
                    else if (!active && wasActive) sessionId.value = ""
                    wasActive = active
                }
            }

            // Tap the violations stream and forward to the sink.
            launch {
                controller.violations.collect { v ->
                    val sid = sessionId.value
                    if (sid.isNotEmpty()) sink.submit(v.toLogEntry(sid))
                }
            }
        }
        sink.close()
    }

    override fun log(event: LockdownViolation) = inner.log(event)
}
