package editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
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
import lockdown.LocalLockdown
import lockdown.copyToClipboard
import theme.Dims
import theme.MonoTextStyle

@Composable
fun SubmissionScreen(
    problem: LabProblemInfo,
    studentEmail: String,
    backend: BackendService,
    modifier: Modifier = Modifier
) {
    var submissions by remember { mutableStateOf<List<SubmissionInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSubmission by remember { mutableStateOf<SubmissionInfo?>(null) }
    val lockdown = LocalLockdown.current

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

    if (selectedSubmission != null) {
        SubmissionCodeView(
            submission = selectedSubmission!!,
            onCopy = {
                val code = selectedSubmission!!.code
                lockdown.recordOwnCopy(code)
                copyToClipboard(code)
            },
            onBack = { selectedSubmission = null },
            modifier = modifier,
        )
    } else {
        SubmissionListView(
            submissions = submissions,
            isLoading = isLoading,
            error = error,
            onRowClick = { selectedSubmission = it },
            modifier = modifier,
        )
    }
}

@Composable
private fun SubmissionCodeView(
    submission: SubmissionInfo,
    onCopy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.height(Dims.toolbarButtonHeight),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to submissions",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Back", style = MaterialTheme.typography.labelMedium)
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = submission.timestamp,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${submission.passed}/${submission.total} passed" +
                        (submission.maxTimeMs?.let { " · ${it.toInt()} ms" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SubmissionStatusBadge(submission.status)

            Spacer(Modifier.width(8.dp))

            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.height(Dims.toolbarButtonHeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Copy", style = MaterialTheme.typography.labelMedium)
            }
        }

        HorizontalDivider()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = submission.code.ifEmpty { "(no code available)" },
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun SubmissionListView(
    submissions: List<SubmissionInfo>,
    isLoading: Boolean,
    error: String?,
    onRowClick: (SubmissionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Past Submissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                }
            }
            submissions.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No submissions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                SubmissionHeaderRow()
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(submissions) { submission ->
                        SubmissionRow(submission, onClick = { onRowClick(submission) })
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
        Text("Date & Time", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
        Text("Score",       style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("Runtime",     style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("Status",      style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SubmissionRow(submission: SubmissionInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(submission.timestamp, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
            Text("${submission.passed}/${submission.total}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(submission.maxTimeMs?.let { "${it.toInt()} ms" } ?: "-", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.weight(1f)) {
                SubmissionStatusBadge(submission.status)
            }
        }
    }
}

@Composable
private fun SubmissionStatusBadge(status: String) {
    val color = when (status) {
        "AC"  -> MaterialTheme.colorScheme.primary
        "WA"  -> MaterialTheme.colorScheme.error
        "TLE" -> MaterialTheme.colorScheme.tertiary
        "CE"  -> MaterialTheme.colorScheme.error
        "RTE" -> MaterialTheme.colorScheme.error
        "MLE" -> MaterialTheme.colorScheme.tertiary
        else  -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (status) {
        "AC"  -> "Accepted"
        "WA"  -> "Wrong Answer"
        "TLE" -> "Time Limit"
        "CE"  -> "Compile Error"
        "RTE" -> "Runtime Error"
        "MLE" -> "Memory Limit"
        else  -> status
    }
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
