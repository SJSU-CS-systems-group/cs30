package labx.data

import kotlinx.serialization.Serializable

enum class ViolationKind {
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
