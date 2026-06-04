package labx.lockdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import labx.data.LockdownViolation
import labx.data.ViolationKind

@Composable
fun LockdownBanner(controller: LockdownController, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<LockdownViolation?>(null) }

    // collectLatest cancels the pending 3s timer on each new violation so the
    // banner always shows the most recent event, never a stale FIFO replay.
    LaunchedEffect(controller) {
        controller.violations.collectLatest { v ->
            if (!isBannerKind(v.kind)) return@collectLatest
            current = v
            delay(3000)
            current = null
        }
    }

    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val v = current
        if (v != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFB00020))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Lockdown violation: ${labelFor(v.kind)}" + (v.detail?.let { " — $it" } ?: ""),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun isBannerKind(kind: ViolationKind): Boolean = when (kind) {
    ViolationKind.FocusLoss,
    ViolationKind.FullscreenExit,
    ViolationKind.TabHidden,
    ViolationKind.PasteFromOutside,
    ViolationKind.ContextMenu,
    ViolationKind.DevToolsAttempt,
    ViolationKind.ClipboardEscape,
    ViolationKind.WindowRestored -> true
    ViolationKind.LockdownStarted,
    ViolationKind.FocusGained,
    ViolationKind.TabVisible,
    ViolationKind.CopyFromEditor,
    ViolationKind.Heartbeat,
    ViolationKind.HeartbeatGap,
    ViolationKind.SessionSummary -> false
}

private fun labelFor(kind: ViolationKind): String = when (kind) {
    ViolationKind.LockdownStarted -> "lockdown started"
    ViolationKind.FocusLoss -> "window lost focus"
    ViolationKind.FullscreenExit -> "exited fullscreen"
    ViolationKind.TabHidden -> "tab hidden"
    ViolationKind.PasteFromOutside -> "paste from outside blocked"
    ViolationKind.ContextMenu -> "right-click blocked"
    ViolationKind.DevToolsAttempt -> "devtools shortcut blocked"
    ViolationKind.ClipboardEscape -> "clipboard scrubbed"
    ViolationKind.WindowRestored -> "minimize blocked"
    ViolationKind.FocusGained -> "focus restored"
    ViolationKind.TabVisible -> "tab visible"
    ViolationKind.CopyFromEditor -> "copy from editor"
    ViolationKind.Heartbeat -> "heartbeat"
    ViolationKind.HeartbeatGap -> "heartbeat gap"
    ViolationKind.SessionSummary -> "session summary"
}
