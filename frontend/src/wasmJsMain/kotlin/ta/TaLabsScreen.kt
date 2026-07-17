package ta

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.TaLabHealthReport
import data.TaLabInfo
import data.TaProblemHealth
import data.TaProblemStatus
import data.TaSectionInfo
import kotlinx.coroutines.launch

@Composable
fun TaLabsScreen(
    section: TaSectionInfo,
    service: TaBackendService
) {
    var labs by remember { mutableStateOf<List<TaLabInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var healthCheckLab by remember { mutableStateOf<TaLabInfo?>(null) }
    var healthReport by remember { mutableStateOf<TaLabHealthReport?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }
    var healthError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(section.courseId) {
        isLoading = true
        loadError = null
        try {
            labs = service.getLabs().filter { it.courseId == section.courseId }
        } catch (e: Exception) {
            loadError = "Failed to load labs"
        }
        isLoading = false
    }

    if (healthCheckLab != null) {
        AlertDialog(
            onDismissRequest = { healthCheckLab = null },
            title = { Text("Lab ${healthCheckLab!!.labNumber} Health Check") },
            text = {
                when {
                    isCheckingHealth -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    healthError != null -> Text(healthError!!, color = MaterialTheme.colorScheme.error)
                    healthReport != null -> LabHealthReportView(healthReport!!)
                }
            },
            confirmButton = {
                TextButton(onClick = { healthCheckLab = null }) {
                    Text("Close")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "${section.courseCode} Section ${section.section} - Labs",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                labs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No labs scheduled for this section",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Column {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    "Lab",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(0.5f)
                                )
                                Text(
                                    "Start",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "End",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "Status",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(0.5f)
                                )
                                Box(modifier = Modifier.width(130.dp))
                            }
                        }
                        HorizontalDivider()
                        LazyColumn {
                            items(labs) { lab ->
                                LabRow(
                                    lab = lab,
                                    onHealthCheck = {
                                        healthCheckLab = lab
                                        healthReport = null
                                        healthError = null
                                        isCheckingHealth = true
                                        scope.launch {
                                            try {
                                                healthReport = service.getLabHealth(lab.labId)
                                            } catch (e: Exception) {
                                                healthError = "Health check failed"
                                            }
                                            isCheckingHealth = false
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabRow(lab: TaLabInfo, onHealthCheck: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Lab ${lab.labNumber}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.5f)
        )
        Text(
            formatLabDateTime(lab.startDateTime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatLabDateTime(lab.endDateTime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box(modifier = Modifier.weight(0.5f)) {
            Surface(
                color = if (lab.isActive) TaGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (lab.isActive) "Active" else "Upcoming",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lab.isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Box(modifier = Modifier.width(130.dp)) {
            OutlinedButton(onClick = onHealthCheck) {
                Text("Health Check")
            }
        }
    }
}

@Composable
private fun LabHealthReportView(report: TaLabHealthReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (report.ok) TaGreen else MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (report.ok) "Ready" else "Not Ready",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Judge: ${if (report.judgeReachable) "reachable" else "unreachable"}, ${if (report.judgeReady) "ready" else "not ready"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        report.detail?.let { detail ->
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            report.problems.forEach { problem ->
                ProblemHealthRow(problem)
            }
        }
    }
}

@Composable
private fun ProblemHealthRow(problem: TaProblemHealth) {
    val (statusLabel, statusColor) = when (problem.status) {
        TaProblemStatus.READY -> "Ready" to TaGreen
        TaProblemStatus.UNVERIFIED -> "Unverified" to MaterialTheme.colorScheme.onSurfaceVariant
        TaProblemStatus.NOT_READY -> "Not Ready" to MaterialTheme.colorScheme.error
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(problem.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Surface(color = statusColor, shape = MaterialTheme.shapes.small) {
                Text(
                    statusLabel,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        val summary = buildString {
            if (problem.verdict != null) {
                append(problem.verdict)
                if (problem.passed != null && problem.total != null) {
                    append(" (${problem.passed}/${problem.total})")
                }
            }
            problem.detail?.let {
                if (isNotEmpty()) append(" — ")
                append(it)
            }
        }
        if (summary.isNotBlank()) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatLabDateTime(dateTimeStr: String?): String {
    if (dateTimeStr == null) return "-"
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
