package labx.lockdown

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import labx.data.LockdownViolation
import labx.data.ViolationKind

interface LockdownEventService {
    suspend fun observe(controller: LockdownController)
    fun log(event: LockdownViolation)
}

// TODO(real-backend): replace println with POST to /lockdown/events;
// ship the SessionSummary event on submit so the teacher dashboard can score the session.
class DummyLockdownEventService(
    private val heartbeatIntervalMs: Long = 10_000L
) : LockdownEventService {

    private var focusLossCount = 0
    private var tabHiddenCount = 0
    private var pastesExternal = 0
    private var copiesFromEditor = 0
    private var fullscreenExits = 0
    private var navAttempts = 0
    private var outMs = 0L
    private var maxHeartbeatGapMs = 0L
    private var lastOutEnterT: Long? = null
    private var lastHeartbeatT: Long = 0L
    private var sessionStartT: Long = 0L

    override suspend fun observe(controller: LockdownController) {
        coroutineScope {
            launch {
                var wasActive = false
                controller.active.collect { active ->
                    if (active && !wasActive) {
                        resetCounters()
                        sessionStartT = currentEpochMs()
                    } else if (!active && wasActive) {
                        emitSessionSummary()
                    }
                    wasActive = active
                }
            }

            launch {
                controller.violations.collect { v ->
                    updateCounters(v)
                    log(v)
                }
            }

            launch {
                lastHeartbeatT = currentEpochMs()
                while (true) {
                    delay(heartbeatIntervalMs)
                    val now = currentEpochMs()
                    val gap = now - lastHeartbeatT
                    if (gap > (heartbeatIntervalMs * 3) / 2) {
                        if (gap > maxHeartbeatGapMs) maxHeartbeatGapMs = gap
                        log(LockdownViolation(ViolationKind.HeartbeatGap, now, "gapMs=$gap"))
                    }
                    lastHeartbeatT = now
                    val tag = if (controller.active.value) "active" else "idle"
                    log(LockdownViolation(ViolationKind.Heartbeat, now, tag))
                }
            }
        }
    }

    override fun log(event: LockdownViolation) {
        // TODO(real-backend): forward `event` to /lockdown/events instead of println.
        val detail = event.detail?.let { " :: $it" } ?: ""
        println("[LockdownEvent] kind=${event.kind} t=${event.timestampMs}$detail")
    }

    private fun updateCounters(v: LockdownViolation) {
        when (v.kind) {
            ViolationKind.FocusLoss -> {
                focusLossCount++
                lastOutEnterT = v.timestampMs
            }
            ViolationKind.TabHidden -> {
                tabHiddenCount++
                lastOutEnterT = v.timestampMs
            }
            ViolationKind.FocusGained, ViolationKind.TabVisible -> {
                lastOutEnterT?.let { start ->
                    outMs += (v.timestampMs - start).coerceAtLeast(0L)
                }
                lastOutEnterT = null
            }
            ViolationKind.PasteFromOutside -> pastesExternal++
            ViolationKind.CopyFromEditor -> copiesFromEditor++
            ViolationKind.FullscreenExit -> fullscreenExits++
            ViolationKind.ContextMenu, ViolationKind.DevToolsAttempt -> navAttempts++
            else -> {}
        }
    }

    private fun emitSessionSummary() {
        lastOutEnterT?.let { start ->
            outMs += (currentEpochMs() - start).coerceAtLeast(0L)
        }
        lastOutEnterT = null
        val durationMs = currentEpochMs() - sessionStartT
        val detail = buildString {
            append("durationMs="); append(durationMs); append(' ')
            append("outMs="); append(outMs); append(' ')
            append("focusLosses="); append(focusLossCount); append(' ')
            append("tabHidden="); append(tabHiddenCount); append(' ')
            append("copiesFromEditor="); append(copiesFromEditor); append(' ')
            append("pastesExternal="); append(pastesExternal); append(' ')
            append("fullscreenExits="); append(fullscreenExits); append(' ')
            append("navAttempts="); append(navAttempts); append(' ')
            append("maxHeartbeatGapMs="); append(maxHeartbeatGapMs)
        }
        log(LockdownViolation(ViolationKind.SessionSummary, currentEpochMs(), detail))
    }

    private fun resetCounters() {
        focusLossCount = 0
        tabHiddenCount = 0
        pastesExternal = 0
        copiesFromEditor = 0
        fullscreenExits = 0
        navAttempts = 0
        outMs = 0L
        maxHeartbeatGapMs = 0L
        lastOutEnterT = null
    }
}
