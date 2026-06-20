package lockdown

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import data.LockdownViolation
import data.ViolationKind
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

    private var lockdownStartMs: Long = 0L
    private val onFullscreenChange: (Event) -> Unit = { handleFullscreenChange() }
    private val onVisibilityChange: (Event) -> Unit = { handleVisibilityChange() }
    private val onBlur: (Event) -> Unit = { handleBlur() }
    private val onFocus: (Event) -> Unit = { handleFocus() }
    private val onContextMenu: (Event) -> Unit = { handleContextMenu(it) }
    private val onKeyDown: (Event) -> Unit = { handleKeyDown(it) }
    private val onCopy: (Event) -> Unit = { handleCopy(it) }
    private val onCut: (Event) -> Unit = { handleCut(it) }
    private val onPaste: (Event) -> Unit = { handlePaste(it) }

    actual fun start() {
        if (state.active.value) return
        lockdownStartMs = currentEpochMs()
        requestFullscreen()
        setLockdownFlag(true)
        document.body?.classList?.add("labx-lockdown")
        document.addEventListener("fullscreenchange", onFullscreenChange)
        document.addEventListener("visibilitychange", onVisibilityChange)
        window.addEventListener("blur", onBlur)
        window.addEventListener("focus", onFocus)
        document.addEventListener("contextmenu", onContextMenu, true)
        document.addEventListener("keydown", onKeyDown, true)
        document.addEventListener("copy", onCopy, true)
        document.addEventListener("cut", onCut, true)
        document.addEventListener("paste", onPaste, true)
        state.setActive(true)
        println("[Clipboard-Web] lockdown START — copy/cut/paste document listeners attached (capture)")
        report(ViolationKind.LockdownStarted)
    }

    actual fun stop(onComplete: () -> Unit) {
        if (!state.active.value) {
            onComplete()
            return
        }
        setLockdownFlag(false)
        document.body?.classList?.remove("labx-lockdown")
        document.removeEventListener("fullscreenchange", onFullscreenChange)
        document.removeEventListener("visibilitychange", onVisibilityChange)
        window.removeEventListener("blur", onBlur)
        window.removeEventListener("focus", onFocus)
        document.removeEventListener("contextmenu", onContextMenu, true)
        document.removeEventListener("keydown", onKeyDown, true)
        document.removeEventListener("copy", onCopy, true)
        document.removeEventListener("cut", onCut, true)
        document.removeEventListener("paste", onPaste, true)
        exitFullscreen()
        state.setActive(false)
        onComplete()
    }

    actual fun report(kind: ViolationKind, detail: String?) {
        state.emit(LockdownViolation(kind, currentEpochMs(), detail))
    }

    actual fun recordOwnCopy(text: String) {
        println("[Clipboard-Web] recordOwnCopy(len=${text.length}) '${text.take(30)}'")
        state.setLastOwnCopy(text)
    }

    actual fun isOwnClipboardText(text: String?): Boolean {
        val result = state.matchesOwnCopy(text)
        println("[Clipboard-Web] isOwnClipboardText(clipLen=${text?.length ?: -1}) -> $result")
        return result
    }

    actual fun setPasteSink(sink: ((String) -> Unit)?) {
        println("[Clipboard-Web] setPasteSink(${if (sink == null) "null — editor blurred" else "editor focused"})")
        state.setPasteSink(sink)
    }

    private fun handleFullscreenChange() {
        if (!state.active.value) return
        if (!isFullscreen()) {
            if (currentEpochMs() - lockdownStartMs < 800L) return
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
        } else {
            report(ViolationKind.TabVisible)
        }
    }

    private fun handleBlur() {
        if (!state.active.value) return
        if (currentEpochMs() - lockdownStartMs < 800L) return
        // Clicking our own problem-panel iframe moves focus *into* it — a same-app focus
        // shift, not leaving the app — so don't flag it as a violation or scrub the clipboard.
        if (activeElementIsFrame()) return
        report(ViolationKind.FocusLoss)
        clearSystemClipboard()
    }

    private fun handleFocus() {
        if (!state.active.value) return
        report(ViolationKind.FocusGained)
        if (!isFullscreen()) requestFullscreen()
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

    // The Compose editor draws to a <canvas>, so its selection is NOT a DOM selection and the
    // browser's default copy/cut would put nothing on the clipboard ("copy doesn't work"). The
    // editor's key handler already recorded the selection via recordOwnCopy(); here we write
    // that text into the real clipboard during the copy/cut event so copy works and the
    // student's own paste later matches. (Recording + CopyFromEditor logging live in the editor
    // key handler, so we don't duplicate them here.)
    private fun handleCopy(e: Event) { println("[Clipboard-Web] 'copy' DOM event fired"); writeOwnCopyToClipboard(e) }
    private fun handleCut(e: Event) { println("[Clipboard-Web] 'cut' DOM event fired"); writeOwnCopyToClipboard(e) }

    private fun writeOwnCopyToClipboard(e: Event) {
        if (!state.active.value) { println("[Clipboard-Web] write skipped — lockdown inactive"); return }
        val own = state.lastOwnCopy()
        if (own.isNullOrEmpty()) {
            println("[Clipboard-Web] write skipped — no recorded own-copy (len=${own?.length ?: -1})")
            return
        }
        setClipboardText(e, own)
        e.preventDefault() // required for setData to take effect
        println("[Clipboard-Web] wrote ${own.length} chars to clipboard via setData + preventDefault")
    }

    // Compose's BasicTextField(state=) does NOT insert pasted text on wasm (issue #4036), so the
    // editor registers a paste sink while focused (LocalLockdown.setPasteSink). When that sink is
    // present this is an editor paste: we own it end-to-end — preventDefault, then insert the
    // student's own copy ourselves, or block + log an outside paste. When no sink is set, the
    // focus is elsewhere (the value-based custom-input, which pastes natively and is gated by its
    // own onValueChange), so we don't touch it — that also avoids double-logging.
    private fun handlePaste(e: Event) {
        if (!state.active.value) return
        val sink = state.pasteSink()
        if (sink == null) {
            println("[Clipboard-Web] paste DOM event ignored — no editor paste sink (custom-input handles its own)")
            return
        }
        e.preventDefault()
        val pasted = readClipboardText(e)
        val matches = state.matchesOwnCopy(pasted)
        println("[Clipboard-Web] editor paste DOM event; clipLen=${pasted?.length ?: -1} matchesOwn=$matches")
        if (matches) {
            sink(pasted ?: "") // insert the student's own copy into the editor
        } else {
            // Log outside pastes with a (truncated) snippet for instructor review. Own pastes
            // are normal editing and are not logged.
            report(ViolationKind.PasteFromOutside, "len=${pasted?.length ?: 0} preview='${pasted?.take(60) ?: ""}' src=editor")
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

private fun activeElementIsFrame(): Boolean =
    js("(document.activeElement != null && document.activeElement.tagName === 'IFRAME')")

private fun setLockdownFlag(on: Boolean): Unit =
    js("window.__labxLockdownOn = on")

private fun readClipboardText(e: Event): String? =
    js("(e && e.clipboardData) ? e.clipboardData.getData('text') : null")

private fun setClipboardText(e: Event, text: String): Unit =
    js("{ if (e && e.clipboardData) { e.clipboardData.setData('text/plain', text); } }")

