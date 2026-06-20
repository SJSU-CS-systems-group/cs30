package editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import data.ViolationKind
import lockdown.LocalLockdown

/**
 * Lockdown clipboard policy for a text field. Integrity goal is *anti-import*: the student may
 * freely cut/copy/paste their own work, but text brought in from outside the app is blocked.
 *
 *  - Cut/Copy: records the field's [selectedText] as the "own copy" so it can be pasted back
 *    anywhere in the app, and logs it (CopyFromEditor, INFO).
 *  - Paste: if the clipboard isn't the student's own copy, the paste is blocked and reported
 *    (PasteFromOutside). Own copies paste through.
 *
 * [selectedText] returns the field's current selection, or null if none / not available
 * (paste-blocking still works via the shared own-copy).
 */
@Composable
fun Modifier.lockdownClipboardGuard(selectedText: () -> String? = { null }): Modifier {
    val lockdown = LocalLockdown.current
    val clipboard = LocalClipboardManager.current
    return onPreviewKeyEvent { e ->
        if (!lockdown.active.value) return@onPreviewKeyEvent false
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (!(e.isCtrlPressed || e.isMetaPressed)) return@onPreviewKeyEvent false
        println("[Clipboard] Compose key reached editor: key=${e.key} ctrl=${e.isCtrlPressed} meta=${e.isMetaPressed}")
        when (e.key) {
            Key.C, Key.X -> {
                val selected = selectedText()
                println("[Clipboard] copy/cut key fired; selectionLen=${selected?.length ?: -1}")
                if (!selected.isNullOrEmpty()) {
                    lockdown.recordOwnCopy(selected)
                    lockdown.report(
                        ViolationKind.CopyFromEditor,
                        "len=${selected.length}${if (e.key == Key.X) " cut=true" else ""}"
                    )
                }
                false // allow the native copy/cut
            }
            Key.V -> {
                val pasted = clipboard.getText()?.text
                val own = lockdown.isOwnClipboardText(pasted)
                println("[Clipboard] paste key fired; clipLen=${pasted?.length ?: -1} isOwn=$own")
                if (!own) {
                    // Log outside pastes with a (truncated) snippet; own pastes aren't logged.
                    lockdown.report(
                        ViolationKind.PasteFromOutside,
                        "len=${pasted?.length ?: 0} preview='${pasted?.take(60) ?: ""}'"
                    )
                    true // block the paste
                } else false
            }
            else -> false
        }
    }
}
