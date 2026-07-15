package ta

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.TaSectionInfo
import data.TaStudentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun TaStudentsScreen(
    section: TaSectionInfo,
    service: TaBackendService
) {
    var showKickDialog by remember { mutableStateOf<TaStudentInfo?>(null) }
    val activeCount = section.students.count { it.status == "active" }

    // Kick dialog
    if (showKickDialog != null) {
        AlertDialog(
            onDismissRequest = { showKickDialog = null },
            title = { Text("Kick Student") },
            text = { Text("Are you sure you want to end the session for ${showKickDialog!!.email}?") },
            confirmButton = {
                Button(
                    onClick = {
                        val student = showKickDialog!!
                        showKickDialog = null
                        student.token?.let { token ->
                            CoroutineScope(Dispatchers.Default).launch {
                                service.kickStudent(token)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Kick")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showKickDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$activeCount active / ${section.students.size} students",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Auto-refresh: 5s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column {
                // Table header
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            "Student Email",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            "Status",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(0.5f)
                        )
                        Text(
                            "Last Login",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Last Logout",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Box(modifier = Modifier.width(60.dp))
                    }
                }
                HorizontalDivider()

                if (section.students.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No students in this section",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(section.students) { student ->
                            StudentRow(
                                student = student,
                                onKick = { showKickDialog = student }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentRow(
    student: TaStudentInfo,
    onKick: () -> Unit
) {
    val isActive = student.status == "active"

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            student.email,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.5f)
        )
        Box(modifier = Modifier.weight(0.5f)) {
            Surface(
                color = if (isActive) TaGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (isActive) "Active" else "Inactive",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Text(
            formatDateTime(student.lastLoginAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatDateTime(student.lastLogoutAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (isActive) {
            IconButton(
                onClick = onKick,
                modifier = Modifier.width(60.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Kick")
            }
        } else {
            Spacer(Modifier.width(60.dp))
        }
    }
}

private fun formatDateTime(dateTimeStr: String?): String {
    if (dateTimeStr == null) return "-"
    // Format: 2024-01-15T10:30:45 -> Jan 15, 10:30
    return try {
        val parts = dateTimeStr.split("T")
        if (parts.size != 2) return dateTimeStr
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")
        if (dateParts.size < 3 || timeParts.size < 2) return dateTimeStr
        val month = when (dateParts[1]) {
            "01" -> "Jan"; "02" -> "Feb"; "03" -> "Mar"; "04" -> "Apr"
            "05" -> "May"; "06" -> "Jun"; "07" -> "Jul"; "08" -> "Aug"
            "09" -> "Sep"; "10" -> "Oct"; "11" -> "Nov"; "12" -> "Dec"
            else -> dateParts[1]
        }
        val day = dateParts[2].toIntOrNull() ?: dateParts[2]
        "$month $day, ${timeParts[0]}:${timeParts[1]}"
    } catch (e: Exception) {
        dateTimeStr
    }
}
