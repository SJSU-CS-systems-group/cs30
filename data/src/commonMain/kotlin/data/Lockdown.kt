package data

import kotlinx.serialization.Serializable

/**
 * Classifies a lockdown event: ALERT = a real violation surfaced to the student (banner) and
 * logged; INFO = lifecycle / recovery / telemetry, logged for audit but never an alert banner.
 * This is the single, model-level source of truth for "is this event banner-worthy?".
 */
enum class ViolationSeverity { ALERT, INFO }

enum class ViolationKind(val severity: ViolationSeverity) {
    LockdownStarted(ViolationSeverity.INFO),    // first event of every session — confirms lockdown engaged
    LockdownEnded(ViolationSeverity.INFO),      // final event of every session — confirms the student ended the lab
    FocusLoss(ViolationSeverity.ALERT),
    FocusGained(ViolationSeverity.INFO),        // recovery: window regained focus
    FullscreenExit(ViolationSeverity.ALERT),
    TabHidden(ViolationSeverity.ALERT),
    TabVisible(ViolationSeverity.INFO),         // recovery: tab became visible again
    PasteFromOutside(ViolationSeverity.ALERT),
    CopyFromEditor(ViolationSeverity.INFO),     // logged for audit, not alerted
    ContextMenu(ViolationSeverity.ALERT),
    DevToolsAttempt(ViolationSeverity.ALERT),
    ClipboardEscape(ViolationSeverity.ALERT),
    WindowRestored(ViolationSeverity.ALERT),    // desktop: minimize was blocked, window forced back to fullscreen
    Heartbeat(ViolationSeverity.INFO),
    HeartbeatGap(ViolationSeverity.INFO),
    SessionSummary(ViolationSeverity.INFO),
}

@Serializable
data class LockdownViolation(
    val kind: ViolationKind,
    val timestampMs: Long,
    val detail: String? = null
)
