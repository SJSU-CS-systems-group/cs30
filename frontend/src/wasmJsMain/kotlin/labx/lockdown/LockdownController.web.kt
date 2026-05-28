package labx.lockdown

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import labx.data.LockdownViolation
import labx.data.ViolationKind
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Web lockdown: fullscreen + visibility/blur listeners + contextmenu/keydown blocks.
 *
 * Known gaps (not preventable from any browser app):
 *   - Devtools opened via browser menu (only shortcut blocks are caught)
 *   - Browser extensions stripping listeners
 *   - Screenshot tools, screen sharing, second monitors
 *   - OS task switching (Cmd+Tab, Alt+Tab) — only detectable via blur/visibility
 *
 * `start()` must be called from a user-gesture handler (Start Lab button click);
 * browsers reject silent requestFullscreen() outside a gesture.
 */
actual class LockdownController {
    private val state = LockdownState()
    actual val violations: SharedFlow<LockdownViolation> get() = state.violations
    actual val active: StateFlow<Boolean> get() = state.active

    private val onFullscreenChange: (Event) -> Unit = { handleFullscreenChange() }
    private val onVisibilityChange: (Event) -> Unit = { handleVisibilityChange() }
    private val onBlur: (Event) -> Unit = { handleBlur() }
    private val onContextMenu: (Event) -> Unit = { handleContextMenu(it) }
    private val onKeyDown: (Event) -> Unit = { handleKeyDown(it) }
    private val onCopy: (Event) -> Unit = { handleCopy(it) }
    private val onCut: (Event) -> Unit = { handleCut(it) }
    private val onPaste: (Event) -> Unit = { handlePaste(it) }

    actual fun start() {
        if (state.active.value) return
        requestFullscreen()
        setLockdownFlag(true)
        document.body?.classList?.add("labx-lockdown")
        document.addEventListener("fullscreenchange", onFullscreenChange)
        document.addEventListener("visibilitychange", onVisibilityChange)
        window.addEventListener("blur", onBlur)
        document.addEventListener("contextmenu", onContextMenu, true)
        document.addEventListener("keydown", onKeyDown, true)
        document.addEventListener("copy", onCopy, true)
        document.addEventListener("cut", onCut, true)
        document.addEventListener("paste", onPaste, true)
        state.setActive(true)
    }

    actual fun stop() {
        if (!state.active.value) return
        setLockdownFlag(false)
        document.body?.classList?.remove("labx-lockdown")
        document.removeEventListener("fullscreenchange", onFullscreenChange)
        document.removeEventListener("visibilitychange", onVisibilityChange)
        window.removeEventListener("blur", onBlur)
        document.removeEventListener("contextmenu", onContextMenu, true)
        document.removeEventListener("keydown", onKeyDown, true)
        document.removeEventListener("copy", onCopy, true)
        document.removeEventListener("cut", onCut, true)
        document.removeEventListener("paste", onPaste, true)
        exitFullscreen()
        state.setActive(false)
    }

    actual fun report(kind: ViolationKind, detail: String?) {
        state.emit(LockdownViolation(kind, nowMs(), detail))
    }

    actual fun recordOwnCopy(text: String) {
        state.setLastOwnCopy(text)
    }

    actual fun isOwnClipboardText(text: String?): Boolean = state.matchesOwnCopy(text)

    private fun handleFullscreenChange() {
        if (!state.active.value) return
        if (!isFullscreen()) {
            report(ViolationKind.FullscreenExit)
            // Best-effort scrub. Browsers reject clipboard writes when the
            // document is unfocused or backgrounded — exactly when scrubbing
            // matters most — so we no longer emit a ClipboardEscape event
            // here to avoid lying about a write that almost certainly failed.
            clearSystemClipboard()
        }
    }

    private fun handleVisibilityChange() {
        if (!state.active.value) return
        if (documentHidden()) {
            report(ViolationKind.TabHidden)
            clearSystemClipboard()
        }
    }

    private fun handleBlur() {
        if (!state.active.value) return
        report(ViolationKind.FocusLoss)
        clearSystemClipboard()
    }

    private fun handleContextMenu(e: Event) {
        if (!state.active.value) return
        e.preventDefault()
        report(ViolationKind.ContextMenu)
    }

    private fun handleKeyDown(e: Event) {
        if (!state.active.value) return
        val ke = e as? KeyboardEvent ?: return
        val key = ke.key
        val devtools =
            key == "F12" ||
            (ke.ctrlKey && ke.shiftKey && (key == "I" || key == "i" || key == "J" || key == "j" || key == "C" || key == "c")) ||
            (ke.metaKey && ke.altKey && (key == "I" || key == "i" || key == "J" || key == "j" || key == "C" || key == "c")) ||
            (ke.ctrlKey && (key == "U" || key == "u"))
        if (devtools) {
            e.preventDefault()
            val chord = buildString {
                if (ke.ctrlKey) append("Ctrl+")
                if (ke.metaKey) append("Cmd+")
                if (ke.altKey) append("Alt+")
                if (ke.shiftKey) append("Shift+")
                append(key)
            }
            report(ViolationKind.DevToolsAttempt, chord)
        }
    }

    private fun handleCopy(e: Event) {
        if (!state.active.value) return
        val text = readClipboardText(e) ?: return
        state.setLastOwnCopy(text)
    }

    private fun handleCut(e: Event) {
        if (!state.active.value) return
        val text = readClipboardText(e) ?: return
        state.setLastOwnCopy(text)
    }

    private fun handlePaste(e: Event) {
        if (!state.active.value) return
        val pasted = readClipboardText(e)
        if (!state.matchesOwnCopy(pasted)) {
            e.preventDefault()
            report(ViolationKind.PasteFromOutside)
        }
    }
}

// --- Kotlin/Wasm JS bridge. Each function body must be a single js() call. ---

private fun requestFullscreen(): Unit =
    js("{ var el = document.documentElement; if (el && el.requestFullscreen) { el.requestFullscreen(); } }")

private fun exitFullscreen(): Unit =
    js("{ if (document.fullscreenElement && document.exitFullscreen) { document.exitFullscreen(); } }")

private fun isFullscreen(): Boolean =
    js("(document.fullscreenElement != null)")

private fun documentHidden(): Boolean =
    js("(!!document.hidden)")

private fun setLockdownFlag(on: Boolean): Unit =
    js("window.__labxLockdownOn = on")

private fun readClipboardText(e: Event): String? =
    js("(e && e.clipboardData) ? e.clipboardData.getData('text') : null")

private fun nowMsRaw(): Double = js("Date.now()")
private fun nowMs(): Long = nowMsRaw().toLong()
