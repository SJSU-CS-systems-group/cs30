package lockdown

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import data.LockdownViolation
import data.ViolationKind

/**
 * Engages and tears down lockdown mode (fullscreen, focus-trap, clipboard scrub,
 * paste-from-outside detection, etc.).
 *
 * Known limitations: OS task switchers, screenshot tools, second monitors, screen sharing,
 * browser extensions, VMs, and devtools opened via browser menu cannot be blocked
 * from a JVM app or a browser. They are detection-only at best and require physical
 * proctoring in the lab.
 */
expect class LockdownController() {
    val violations: SharedFlow<LockdownViolation>
    val active: StateFlow<Boolean>

    /** Engage all platform restrictions. Idempotent. */
    fun start()

    /** Tear down restrictions and listeners. Idempotent. onComplete fires after window restoration (desktop) or immediately (web). */
    fun stop(onComplete: () -> Unit = {})

    /** Emit a violation. Called by platform code or by the in-editor paste guard. */
    fun report(kind: ViolationKind, detail: String? = null)

    /** Record a copy/cut made by the student inside the editor. */
    fun recordOwnCopy(text: String)

    /** Returns true if the given clipboard text was produced by the editor itself. */
    fun isOwnClipboardText(text: String?): Boolean

    /**
     * Register how to insert text into the currently-focused editor field, or null when it
     * loses focus. On web the DOM paste handler uses this to insert an allowed (own) paste,
     * because Compose's `BasicTextField(state=)` does not perform clipboard paste on wasm.
     * Unused on desktop (native paste works there).
     */
    fun setPasteSink(sink: ((String) -> Unit)?)

    /**
     * Register a predicate for pastes that should be allowed even though they aren't the
     * student's own in-editor copy — e.g. "this text appears in the current problem statement."
     * The problem panel is a separate iframe/WebView surface the clipboard guard can't observe
     * directly, so this is how legitimate problem-statement copy/paste gets recognized. Called by
     * CodeEditorScreen whenever the loaded problem changes; pass null to clear. Consulted by both
     * the in-editor paste guard and (on web) the DOM paste handler, alongside isOwnClipboardText.
     */
    fun setExternalPasteAllowlist(predicate: ((String) -> Boolean)?)

    /** True if [text] isn't the student's own copy but is still an allowed paste per the
     *  registered [setExternalPasteAllowlist] predicate. False when none is registered. */
    fun isAllowedExternalText(text: String?): Boolean
}

/** Shared common state used by both expect implementations. */
class LockdownState {
    private val _violations = MutableSharedFlow<LockdownViolation>(extraBufferCapacity = 32)
    val violations: SharedFlow<LockdownViolation> = _violations.asSharedFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var lastOwnCopy: String? = null
    private var pasteSink: ((String) -> Unit)? = null
    private var externalPasteAllowlist: ((String) -> Boolean)? = null

    fun setActive(value: Boolean) {
        _active.value = value
    }

    fun emit(v: LockdownViolation) {
        _violations.tryEmit(v)
    }

    fun setLastOwnCopy(text: String) {
        lastOwnCopy = text
    }

    /** The student's last in-editor copy, or null. Used on web to write it to the real clipboard. */
    fun lastOwnCopy(): String? = lastOwnCopy

    fun matchesOwnCopy(text: String?): Boolean {
        if (text.isNullOrEmpty()) return true
        return text == lastOwnCopy
    }

    fun setPasteSink(sink: ((String) -> Unit)?) {
        pasteSink = sink
    }

    /** How to insert text into the focused editor field, or null if none is focused. */
    fun pasteSink(): ((String) -> Unit)? = pasteSink

    fun setExternalPasteAllowlist(predicate: ((String) -> Boolean)?) {
        externalPasteAllowlist = predicate
    }

    /** True when [text] isn't the student's own in-editor copy but is still an allowed paste
     *  (e.g. it matches the current problem statement). False with no allowlist registered. */
    fun matchesAllowedExternalText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return true
        return externalPasteAllowlist?.invoke(text) == true
    }
}

val LocalLockdown = staticCompositionLocalOf<LockdownController> {
    error("LockdownController not provided. Wrap with CompositionLocalProvider(LocalLockdown provides ...).")
}
