package labx.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import labx.data.Student
import labx.lockdown.LocalLockdown

@Composable
fun TopBar(
    student: Student,
    isProblemPanelOpen: Boolean,
    onTogglePanel: () -> Unit,
    onSubmitExit: () -> Unit
) {
    val lockdown = LocalLockdown.current
    val locked by lockdown.active.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onTogglePanel) {
            Text(
                text = "☰",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.width(4.dp))

        Text(
            text = "CS30",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )

        Text(
            text = "  ·  Baby Shark",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )

        if (locked) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "LOCKDOWN",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFFFD54F)
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "${student.name}  ${student.email}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f)
        )

        Spacer(Modifier.width(12.dp))

        if (locked) {
            Button(
                onClick = onSubmitExit,
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020))
            ) {
                Text(
                    text = "Submit & End Lab",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else {
            OutlinedButton(
                onClick = onSubmitExit,
                modifier = Modifier.height(32.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    text = "Logout",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
}
