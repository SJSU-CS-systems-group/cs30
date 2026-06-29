package editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import backend.BackendService
import backend.SubmissionsRequest
import data.LabProblemInfo
import data.SubmissionInfo
import kotlinx.coroutines.launch

@Composable
fun SubmissionScreen(
    problem: LabProblemInfo,
    studentEmail: String,
    backend: BackendService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var submissions by remember { mutableStateOf<List<SubmissionInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fetch submissions on load
    LaunchedEffect(problem.slug) {
        isLoading = true
        error = null
        try {
            submissions = backend.listSubmissions(
                SubmissionsRequest(
                    courseId = problem.courseId,
                    section = problem.section,
                    labNumber = problem.labNumber,
                    problemName = problem.slug,
                    studentEmail = studentEmail
                )
            )
        } catch (e: Exception) {
            error = e.message ?: "Failed to load submissions"
        }
        isLoading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Past Submissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

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
                        text = error!!,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            submissions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No submissions yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                // Header row
                SubmissionHeaderRow()

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Submission list
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(submissions) { submission ->
                        SubmissionRow(submission)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmissionHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Date & Time",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = "Score",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Runtime",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Status",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SubmissionRow(submission: SubmissionInfo) {
    val statusColor = when (submission.status) {
        "AC" -> MaterialTheme.colorScheme.primary
        "WA" -> MaterialTheme.colorScheme.error
        "TLE" -> MaterialTheme.colorScheme.tertiary
        "CE" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when (submission.status) {
        "AC" -> "Accepted"
        "WA" -> "Wrong Answer"
        "TLE" -> "Time Limit"
        "CE" -> "Compile Error"
        "RTE" -> "Runtime Error"
        "MLE" -> "Memory Limit"
        else -> submission.status
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Date & Time
            Text(
                text = submission.timestamp,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(2f)
            )

            // Score
            Text(
                text = "${submission.passed}/${submission.total}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            // Runtime
            Text(
                text = submission.maxTimeMs?.let { "${it.toInt()} ms" } ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )

            // Status badge
            Box(
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
