package editor

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.Student
import lockdown.LocalLockdown
import theme.AppTheme
import theme.Dims

@Composable
fun EditorTopBar(
    student: Student,
    problemTitle: String,
    remainingMs: Long? = null,
    isFocusMode: Boolean = false,
    onToggleFocusMode: () -> Unit = {},
    currentTheme: AppTheme = AppTheme.LIGHT,
    onThemeChange: (AppTheme) -> Unit = {},
    onSubmitExit: () -> Unit
) {
    val lockdown = LocalLockdown.current
    val locked by lockdown.active.collectAsState()

    if (isFocusMode) {
        AppTopBar(
            title = "CS30",
            subtitle = problemTitle,
            trailingContent = {
                Spacer(Modifier.weight(1f))
                LabTimerChip(remainingMs = remainingMs)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onToggleFocusMode,
                    modifier = Modifier.height(Dims.toolbarButtonHeight),
                ) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = "Exit focus mode",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Exit Focus Mode", style = MaterialTheme.typography.labelMedium)
                }
            }
        )
    } else {
        AppTopBar(
            title = "CS30",
            subtitle = problemTitle,
            studentName = "${student.name}  ${student.email}",
            trailingContent = {
                if (locked) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "LOCKDOWN",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(8.dp))
                LabTimerChip(remainingMs = remainingMs)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onToggleFocusMode,
                    modifier = Modifier.height(Dims.toolbarButtonHeight),
                ) {
                    Icon(
                        Icons.Filled.CenterFocusStrong,
                        contentDescription = "Enter focus mode",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Focus", style = MaterialTheme.typography.labelMedium)
                }
                SettingsDropdown(currentTheme = currentTheme, onThemeChange = onThemeChange)
                Spacer(Modifier.width(4.dp))
                if (locked) {
                    Button(
                        onClick = onSubmitExit,
                        modifier = Modifier.height(Dims.toolbarButtonHeight),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = null, modifier = Modifier.width(18.dp), tint = MaterialTheme.colorScheme.onError)
                        Spacer(Modifier.width(4.dp))
                        Text("End Lab", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
}
