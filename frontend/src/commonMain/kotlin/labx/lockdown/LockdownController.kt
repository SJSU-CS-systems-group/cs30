package labx.lockdown

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import labx.data.LockdownViolation
import labx.data.ViolationKind

/**
 * Engages and tears down lockdown mode (fullscreen, focus-trap, clipboard scrub,
 * paste-from-outside detection, etc.).
 *
 * Known limitations — see /Users/spartan/.claude/plans/how-can-a-lockdown-composed-owl.md §9.
 * In short: OS task switchers, screenshot tools, second monitors, screen sharing,
 * browser extensions, VMs, and devtools opened via browser menu cannot be blocked
 * from a JVM app or a browser. They are detection-only at best and require physical
 * proctoring in the lab.
 */
expect class LockdownController() {
    val violations: SharedFlow<LockdownViolation>
    val active: StateFlow<Boolean>

    /** Engage all platform restrictions. Idempotent. */
    fun start()

    /** Tear down restrictions and listeners. Idempotent. */
    fun stop()

    /** Emit a violation. Called by platform code or by the in-editor paste guard. */
    fun report(kind: ViolationKind, detail: String? = null)

    /** Record a copy/cut made by the student inside the editor. */
    fun recordOwnCopy(text: String)

    /** Returns true if the given clipboard text was produced by the editor itself. */
    fun isOwnClipboardText(text: String?): Boolean
}

/** Shared common state used by both expect implementations. */
class LockdownState {
    private val _violations = MutableSharedFlow<LockdownViolation>(extraBufferCapacity = 32)
    val violations: SharedFlow<LockdownViolation> = _violations.asSharedFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var lastOwnCopy: String? = null

    fun setActive(value: Boolean) {
        _active.value = value
    }

    fun emit(v: LockdownViolation) {
        _violations.tryEmit(v)
    }

    fun setLastOwnCopy(text: String) {
        lastOwnCopy = text
    }

    fun matchesOwnCopy(text: String?): Boolean {
        if (text.isNullOrEmpty()) return true
        return text == lastOwnCopy
    }
}

val LocalLockdown = staticCompositionLocalOf<LockdownController> {
    error("LockdownController not provided. Wrap with CompositionLocalProvider(LocalLockdown provides ...).")
}
