package data

import kotlinx.serialization.Serializable

enum class ViolationKind {
    LockdownStarted,    // first event of every session — confirms lockdown engaged
    LockdownEnded,      // final event of every session — confirms the student ended the lab
    FocusLoss,
    FocusGained,
    FullscreenExit,
    TabHidden,
    TabVisible,
    PasteFromOutside,
    CopyFromEditor,
    ContextMenu,
    DevToolsAttempt,
    ClipboardEscape,
    WindowRestored,     // desktop: minimize was blocked, window forced back to fullscreen
    Heartbeat,
    HeartbeatGap,
    SessionSummary,
}

@Serializable
data class LockdownViolation(
    val kind: ViolationKind,
    val timestampMs: Long,
    val detail: String? = null
)
