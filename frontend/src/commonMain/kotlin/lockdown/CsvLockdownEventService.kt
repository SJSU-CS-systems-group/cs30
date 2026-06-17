package lockdown

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import data.LockdownViolation
import data.ViolationKind

private val CSV_EXCLUDED_KINDS = setOf(ViolationKind.Heartbeat, ViolationKind.CopyFromEditor)

/**
 * Wraps DummyLockdownEventService (composition, not subclass — it's final) and
 * fans each relevant event to a per-session ActivityLogSink.
 *
 * Each lockdown activation creates a fresh sink via [hook.onSessionStart]. The
 * problem slug is captured at start time (when the problem is guaranteed open)
 * to avoid a race with the UI nulling selectedProblem during stop(). On
 * deactivation the sink is flushed then [hook.onSessionEnd] triggers the git
 * commit. Heartbeat and CopyFromEditor are excluded from the CSV to keep the
 * audit file concise.
 */
class CsvLockdownEventService(
    private val hook: ActivityLogSessionHook,
    private val problemSlug: () -> String?,
) : LockdownEventService {
    private val inner = DummyLockdownEventService()
    private val sessionId = MutableStateFlow("")

    override suspend fun observe(controller: LockdownController) {
        var currentSink: ActivityLogSink = ConsoleActivityLogSink()
        var capturedSlug = "unknown"
        coroutineScope {
            launch { inner.observe(controller) }

            launch {
                var wasActive = false
                controller.active.collect { active ->
                    if (active && !wasActive) {
                        val sid = "session-${currentEpochMs()}"
                        capturedSlug = problemSlug() ?: "unknown"
                        sessionId.value = sid
                        currentSink = hook.onSessionStart(sid, capturedSlug)
                    } else if (!active && wasActive) {
                        val sid = sessionId.value
                        sessionId.value = ""
                        // Record the lab-end action into this session before flushing the sink.
                        currentSink.submit(
                            LockdownViolation(ViolationKind.LockdownEnded, currentEpochMs()).toLogEntry(sid)
                        )
                        currentSink.close()
                        hook.onSessionEnd(sid, capturedSlug)
                    }
                    wasActive = active
                }
            }

            launch {
                controller.violations.collect { v ->
                    val sid = sessionId.value
                    if (sid.isNotEmpty() && v.kind !in CSV_EXCLUDED_KINDS) {
                        currentSink.submit(v.toLogEntry(sid))
                    }
                }
            }
        }
        currentSink.close()
    }

    override fun log(event: LockdownViolation) = inner.log(event)
}
