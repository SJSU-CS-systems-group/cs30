package lockdown

import androidx.compose.ui.unit.dp
import data.ViolationKind
import kotlin.time.Duration.Companion.seconds

/** How long a violation banner stays before auto-dismissing. */
internal val BANNER_AUTO_DISMISS = 3.seconds
internal val BANNER_H_PADDING = 16.dp
internal val BANNER_V_PADDING = 8.dp

/**
 * Human-readable banner text for a violation kind. Whether a kind shows as a banner is decided
 * by [ViolationKind.severity] (ALERT), not here — this only maps kind → display text.
 */
internal fun violationLabel(kind: ViolationKind): String = when (kind) {
    ViolationKind.FocusLoss -> "window lost focus"
    ViolationKind.FullscreenExit -> "exited fullscreen"
    ViolationKind.TabHidden -> "tab hidden"
    ViolationKind.PasteFromOutside -> "paste from outside blocked"
    ViolationKind.ContextMenu -> "right-click blocked"
    ViolationKind.DevToolsAttempt -> "devtools shortcut blocked"
    ViolationKind.ClipboardEscape -> "clipboard scrubbed"
    ViolationKind.WindowRestored -> "minimize blocked"
    ViolationKind.LockdownStarted -> "lockdown started"
    ViolationKind.LockdownEnded -> "lockdown ended"
    ViolationKind.FocusGained -> "focus restored"
    ViolationKind.TabVisible -> "tab visible"
    ViolationKind.CopyFromEditor -> "copy from editor"
    ViolationKind.Heartbeat -> "heartbeat"
    ViolationKind.HeartbeatGap -> "heartbeat gap"
    ViolationKind.SessionSummary -> "session summary"
}
