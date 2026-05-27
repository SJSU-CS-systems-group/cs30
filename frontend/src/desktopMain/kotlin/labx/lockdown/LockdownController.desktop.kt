package labx.lockdown

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import labx.data.LockdownViolation
import labx.data.ViolationKind
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Desktop lockdown: maximized + always-on-top + focus trap + AWT key-event dispatcher.
 *
 * Known gaps (not preventable from pure JVM):
 *   - OS task switchers: Cmd+Tab, Alt+Tab, Mission Control, Win+D
 *   - Screenshot tools: Cmd+Shift+4, Snipping Tool, Win+Shift+S
 *   - External monitors / screen sharing / Zoom share
 *   - Anything outside the JVM process
 *
 * Detection contract: window focus loss fires FocusLoss + scrubs the clipboard.
 */
actual class LockdownController {
    private val state = LockdownState()
    actual val violations: SharedFlow<LockdownViolation> get() = state.violations
    actual val active: StateFlow<Boolean> get() = state.active

    /** Must be set from main.kt before start(). */
    var window: ComposeWindow? = null

    private var focusListener: WindowFocusListener? = null
    private var keyDispatcher: KeyEventDispatcher? = null
    private var previousPlacement: WindowPlacement = WindowPlacement.Floating
    private var previousAlwaysOnTop: Boolean = false
    private var previousResizable: Boolean = true

    actual fun start() {
        if (state.active.value) return
        val w = window ?: return
        previousPlacement = w.placement
        previousAlwaysOnTop = w.isAlwaysOnTop
        previousResizable = w.isResizable
        w.placement = WindowPlacement.Maximized
        w.isAlwaysOnTop = true
        w.isResizable = false

        val fl = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {}
            override fun windowLostFocus(e: WindowEvent?) {
                if (!state.active.value) return
                report(ViolationKind.FocusLoss)
                clearSystemClipboard()
                state.emit(
                    LockdownViolation(
                        ViolationKind.ClipboardEscape,
                        System.currentTimeMillis(),
                        "scrubbed on focus loss"
                    )
                )
                w.toFront()
                w.requestFocus()
            }
        }
        w.addWindowFocusListener(fl)
        focusListener = fl

        val kd = KeyEventDispatcher { e ->
            if (!state.active.value) return@KeyEventDispatcher false
            if (e.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val swallow = when {
                e.keyCode == KeyEvent.VK_F11 -> true
                e.keyCode == KeyEvent.VK_F12 -> true
                e.keyCode == KeyEvent.VK_F4 && e.isAltDown -> true
                e.keyCode == KeyEvent.VK_Q && e.isMetaDown -> true
                else -> false
            }
            if (swallow) report(ViolationKind.DevToolsAttempt, "key ${KeyEvent.getKeyText(e.keyCode)}")
            swallow
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(kd)
        keyDispatcher = kd

        state.setActive(true)
    }

    actual fun stop() {
        if (!state.active.value) return
        val w = window
        focusListener?.let { w?.removeWindowFocusListener(it) }
        focusListener = null
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        keyDispatcher = null
        if (w != null) {
            w.placement = previousPlacement
            w.isAlwaysOnTop = previousAlwaysOnTop
            w.isResizable = previousResizable
        }
        state.setActive(false)
    }

    actual fun report(kind: ViolationKind, detail: String?) {
        state.emit(LockdownViolation(kind, System.currentTimeMillis(), detail))
    }

    actual fun recordOwnCopy(text: String) {
        state.setLastOwnCopy(text)
    }

    actual fun isOwnClipboardText(text: String?): Boolean = state.matchesOwnCopy(text)
}
