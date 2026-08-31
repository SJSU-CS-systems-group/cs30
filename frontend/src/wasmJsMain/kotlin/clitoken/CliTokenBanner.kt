package clitoken

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import lockdown.copyToClipboard
import theme.LocalEditorPalette

/** How long the Copy button stays in its "Copied" state before reverting. */
private const val COPY_FEEDBACK_DURATION_MS = 1500L

/** True if the user confirmed the browser's native confirm dialog. */
fun confirmAction(message: String): Boolean = js("window.confirm(message)")

/**
 * Reveals a just-(re)generated CLI token once, or offers to reset it once it's no longer
 * recoverable (the server only ever stores a salted hash - see CliTokenService). Shared between
 * the admin and TA dashboards, since both mint a CLI token the same way on first login.
 * onReset is called after the user confirms - the caller is responsible for hitting the
 * POST .../cli-token?reset=true endpoint and updating rawToken with the result.
 */
@Composable
fun CliTokenBanner(rawToken: String?, onReset: () -> Unit, accentColor: Color) {
    if (rawToken != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "You can only see this token once - save it now. If you lose it, you'll have to reset it.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Kept selectable alongside the Copy button: dragging still works for anyone who
                // wants only part of the token, or whose browser denies clipboard access.
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rawToken,
                        modifier = Modifier.padding(vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.width(12.dp))
                CopyTokenButton(rawToken)
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Copy the token above (or select it manually) - it is used for CLI commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your CLI token was already generated on a previous login and can't be shown again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                OutlinedButton(
                    onClick = {
                        if (confirmAction("This will invalidate the current token - anything still using it will stop working. Continue?")) {
                            onReset()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                ) {
                    Text("Reset Token")
                }
            }
        }
    }
}

/**
 * One-click copy with a brief confirmation. The clipboard write itself is silent (and can be
 * refused by the browser), so the icon/label swap is the only signal the user gets that the token
 * they can never see again is actually saved.
 */
@Composable
private fun CopyTokenButton(rawToken: String) {
    val palette = LocalEditorPalette.current
    var justCopied by remember { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPY_FEEDBACK_DURATION_MS)
            justCopied = false
        }
    }

    TextButton(
        onClick = {
            copyToClipboard(rawToken)
            justCopied = true
        }
    ) {
        Icon(
            imageVector = if (justCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = if (justCopied) "Token copied" else "Copy token",
            modifier = Modifier.size(16.dp),
            tint = if (justCopied) palette.pass else LocalContentColor.current
        )
        Spacer(Modifier.width(4.dp))
        Text(if (justCopied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
    }
}
