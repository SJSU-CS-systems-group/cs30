package editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.ViolationKind
import lockdown.LocalLockdown
import theme.MonoTextStyle

@Composable
fun CustomInputPanel(
    current: String,
    onCurrentChange: (String) -> Unit,
    cases: List<String>,
    onAddCase: () -> Unit,
    onRemoveCase: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lockdown = LocalLockdown.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = current,
                // The Material field ignores our key/clipboard-event blocking on web, so gate
                // the change itself: a bulk insertion (a paste) that isn't the student's own
                // copy is an outside paste — reject it (don't propagate) and log a snippet.
                // Own-copy pastes and normal typing pass through.
                onValueChange = onChange@{ newValue ->
                    if (lockdown.active.value && newValue.length - current.length > 1) {
                        val chunk = insertedChunk(current, newValue)
                        val own = lockdown.isOwnClipboardText(chunk)
                        println("[Clipboard] custom-input bulk insert len=${chunk.length} own=$own")
                        if (!own) {
                            lockdown.report(
                                ViolationKind.PasteFromOutside,
                                "len=${chunk.length} preview='${chunk.take(60)}' field=customInput"
                            )
                            return@onChange // reject the paste
                        }
                    }
                    onCurrentChange(newValue)
                },
                label = { Text("Custom Input (stdin)") },
                placeholder = { Text("Type stdin for Run, or click Add to queue a test case") },
                singleLine = false,
                textStyle = MonoTextStyle,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 88.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onAddCase,
                enabled = current.isNotBlank() && cases.size < maxCustomTestCases,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Add test case") }
        }

        if (cases.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Custom test cases (${cases.size}/$maxCustomTestCases)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cases.forEachIndexed { index, case ->
                    CustomCaseRow(
                        index = index + 1,
                        text = case,
                        onRemove = { onRemoveCase(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomCaseRow(index: Int, text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$index",
            style = MonoTextStyle.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = text,
            style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove test case", tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** The text inserted when [old] became [new] (common-prefix/suffix diff). Used to classify a paste. */
private fun insertedChunk(old: String, new: String): String {
    if (new.length <= old.length) return ""
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return new.substring(prefix, new.length - suffix)
}
