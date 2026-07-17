package ta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.TaActivityLogEntry
import kotlinx.coroutines.launch

private val AlertRed = Color(0xFFFFEBEE)
private val AlertRedText = Color(0xFFC62828)
private val InfoBlue = Color(0xFFE3F2FD)
private val InfoBlueText = Color(0xFF1565C0)

@Composable
fun TaActivityLogScreen(
    studentEmail: String,
    courseId: String,
    service: TaBackendService,
    onBack: () -> Unit
) {
    var entries by remember { mutableStateOf<List<TaActivityLogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(studentEmail, courseId) {
        isLoading = true
        error = null
        try {
            entries = service.getActivityLog(courseId, studentEmail)
        } catch (e: Exception) {
            error = "Failed to load activity log"
        }
        isLoading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Activity Log",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    studentEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            // Only the delta since the newest entry we're already showing —
                            // new entries are newer, so they belong on top of the (desc-sorted) list.
                            val sinceMs = entries.maxOfOrNull { it.timestampMs } ?: 0
                            val newEntries = service.getActivityLog(courseId, studentEmail, sinceMs)
                            entries = newEntries + entries
                        } catch (e: Exception) {
                            error = "Failed to refresh"
                        }
                        isLoading = false
                    }
                }) {
                    Text("Refresh")
                }
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }

        // Legend
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(AlertRed, MaterialTheme.shapes.small)
                )
                Spacer(Modifier.width(4.dp))
                Text("Violation", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(InfoBlue, MaterialTheme.shapes.small)
                )
                Spacer(Modifier.width(4.dp))
                Text("Info", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${entries.count { it.severity == "ALERT" }} violations today",
                style = MaterialTheme.typography.bodySmall,
                color = if (entries.any { it.severity == "ALERT" }) AlertRedText else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Content
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No activity logged today",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn {
                        items(entries) { entry ->
                            ActivityLogRow(entry)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogRow(entry: TaActivityLogEntry) {
    val isAlert = entry.severity == "ALERT"
    val backgroundColor = if (isAlert) AlertRed else InfoBlue
    val textColor = if (isAlert) AlertRedText else InfoBlueText

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Time
        Text(
            formatTime(entry.timestampIso),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(70.dp)
        )

        Spacer(Modifier.width(12.dp))

        // Event kind badge
        Surface(
            color = if (isAlert) AlertRedText else InfoBlueText,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                entry.eventKind,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.width(12.dp))

        // Details
        Column(modifier = Modifier.weight(1f)) {
            if (entry.problem.isNotBlank()) {
                Text(
                    entry.problem,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.8f)
                )
            }
        }

        // Platform
        Text(
            entry.platform,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.6f)
        )
    }
}

private fun formatTime(timestampIso: String): String {
    // Format: 2024-01-15T10:30:45.123Z -> 10:30:45
    return try {
        val timePart = timestampIso.substringAfter("T").substringBefore(".")
        val parts = timePart.split(":")
        if (parts.size >= 3) {
            "${parts[0]}:${parts[1]}:${parts[2].take(2)}"
        } else {
            timePart
        }
    } catch (e: Exception) {
        timestampIso
    }
}
