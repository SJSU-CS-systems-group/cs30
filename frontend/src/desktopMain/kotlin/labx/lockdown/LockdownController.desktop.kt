package labx.lockdown

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import labx.data.LockdownViolation
import labx.data.ViolationKind
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import javax.swing.SwingUtilities

/**
 * Desktop lockdown: fills entire screen + always-on-top + focus trap + AWT key-event dispatcher.
 * Window mutations are deferred to SwingUtilities.invokeLater to avoid Compose render reentry crashes.
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
    private var stateListener: WindowStateListener? = null
    private var previousBounds: Rectangle = Rectangle(0, 0, 1280, 800)
    private var previousExtendedState: Int = Frame.NORMAL
    private var previousAlwaysOnTop: Boolean = false
    private var previousResizable: Boolean = true
    private var lockdownStartMs: Long = 0L

    actual fun start() {
        if (state.active.value) return
        val w = window ?: run {
            println("[LockdownController] ❌ window is null, cannot start lockdown")
            return
        }
        println("[LockdownController] 🔒 Starting lockdown")

        // Save state + register listeners synchronously (lightweight, no AWT painting)
        savePreviousState(w)
        lockdownStartMs = currentEpochMs()
        setupListeners(w)
        state.setActive(true)
        report(ViolationKind.LockdownStarted)

        // Defer window mutations to next AWT cycle — avoids Compose render reentry crash
        SwingUtilities.invokeLater { applyLockdownWindow(w) }
    }

    actual fun stop() {
        if (!state.active.value) return
        teardownListeners()
        state.setActive(false)
        val w = window ?: return
        SwingUtilities.invokeLater { restoreWindow(w) }
    }

    // --- Private helpers: window state management ---

    private fun savePreviousState(w: ComposeWindow) {
        previousBounds = Rectangle(w.bounds)
        previousExtendedState = w.extendedState
        previousAlwaysOnTop = w.isAlwaysOnTop
        previousResizable = w.isResizable
    }

    private fun applyLockdownWindow(w: ComposeWindow) {
        try {
            // 1. Hide Dock + menu bar + block Cmd+Tab FIRST — must happen before sizing
            applyMacOSLockdownPresentation()

            // 2. Use raw screen size — Toolkit.getScreenSize() returns full pixel dimensions
            //    regardless of Dock/menu bar. defaultConfiguration.bounds excludes them.
            val screenSize = Toolkit.getDefaultToolkit().getScreenSize()
            println("[LockdownController] 📐 Full screen size: ${screenSize.width}×${screenSize.height}")

            // 3. Frame.NORMAL + explicit bounds covers the full display
            //    (MAXIMIZED_BOTH clips to usable area, leaving Dock gap)
            w.extendedState = Frame.NORMAL
            w.setBounds(0, 0, screenSize.width, screenSize.height)
            w.isAlwaysOnTop = true
            w.isResizable = false
            w.toFront()
            w.requestFocus()
            println("[LockdownController] 🔒 Lockdown: window covers full display (${screenSize.width}×${screenSize.height}), Dock + menu bar hidden")
        } catch (e: Exception) {
            println("[LockdownController] ❌ Failed to apply lockdown window: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun restoreWindow(w: ComposeWindow) {
        try {
            val maxBounds = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getMaximumWindowBounds()   // safe area: excludes macOS menu bar + Dock

            val safeY = previousBounds.y.coerceAtLeast(maxBounds.y)
            val safeBounds = Rectangle(previousBounds.x, safeY, previousBounds.width, previousBounds.height)

            println("[LockdownController] 🔓 Restoring window: previousBounds=$previousBounds maxBounds.y=${maxBounds.y} safeY=$safeY previousExtendedState=$previousExtendedState")

            // Frame.NORMAL must come BEFORE setBounds — on macOS, setBounds is ignored if MAXIMIZED_BOTH is still set
            w.extendedState = Frame.NORMAL
            println("[LockdownController] → Set extendedState = NORMAL (was MAXIMIZED_BOTH during lockdown)")
            w.setBounds(safeBounds)
            println("[LockdownController] → Called setBounds($safeBounds)")
            w.extendedState = previousExtendedState   // restore original state after bounds are applied
            println("[LockdownController] → Restored extendedState = $previousExtendedState")
            w.isAlwaysOnTop = previousAlwaysOnTop
            w.isResizable = previousResizable
            println("[LockdownController] → alwaysOnTop=$previousAlwaysOnTop, resizable=$previousResizable")
            restoreMacOSPresentation()
            println("[LockdownController] ✅ Window fully restored: $safeBounds (extendedState=$previousExtendedState)")
        } catch (e: Exception) {
            println("[LockdownController] ❌ Failed to restore window: ${e.message}")
            e.printStackTrace()
        }
    }

    // --- Private helpers: listener setup/teardown ---

    private fun setupListeners(w: ComposeWindow) {
        // Focus listener: detects when window loses focus, triggers focus trap
        val fl = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                if (!state.active.value) return
                report(ViolationKind.FocusGained)
            }
            override fun windowLostFocus(e: WindowEvent?) {
                if (!state.active.value) return
                if (currentEpochMs() - lockdownStartMs < 800L) return
                report(ViolationKind.FocusLoss)
                clearSystemClipboard()
                state.emit(
                    LockdownViolation(
                        ViolationKind.ClipboardEscape,
                        currentEpochMs(),
                        "scrubbed on focus loss"
                    )
                )
                w.toFront()
                w.requestFocus()
            }
        }
        w.addWindowFocusListener(fl)
        focusListener = fl

        // State listener: detects minimize, restores lockdown window
        val sl = WindowStateListener { e ->
            if (e.newState and Frame.ICONIFIED != 0) {
                println("[LockdownController] 🚫 Minimize detected, restoring lockdown window")
                report(ViolationKind.WindowRestored, "minimize blocked")
                w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
                SwingUtilities.invokeLater { applyLockdownWindow(w) }
            }
        }
        w.addWindowStateListener(sl)
        stateListener = sl

        // Key dispatcher: blocks devtools shortcuts
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
    }

    private fun teardownListeners() {
        focusListener?.let { window?.removeWindowFocusListener(it) }
        focusListener = null
        stateListener?.let { window?.removeWindowStateListener(it) }
        stateListener = null
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        keyDispatcher = null
    }

    actual fun report(kind: ViolationKind, detail: String?) {
        state.emit(LockdownViolation(kind, currentEpochMs(), detail))
    }

    actual fun recordOwnCopy(text: String) {
        state.setLastOwnCopy(text)
    }

    actual fun isOwnClipboardText(text: String?): Boolean = state.matchesOwnCopy(text)
}
