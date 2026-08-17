package editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import backend.BackendService
import backend.SubmissionsRequest
import data.LabProblemInfo
import data.SubmissionInfo
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import theme.Dims
import theme.LocalEditorPalette
import theme.MonoTextStyle

private fun formatTimestamp(isoUtc: String): String {
    return try {
        val instant = Instant.parse(isoUtc)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val hour = local.hour
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val minute = local.minute.toString().padStart(2, '0')
        "$month ${local.dayOfMonth}, ${local.year} · $displayHour:$minute $amPm"
    } catch (_: Exception) {
        isoUtc
    }
}

@Composable
fun SubmissionScreen(
    problem: LabProblemInfo,
    studentEmail: String,
    backend: BackendService,
    onLoadIntoEditor: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var submissions by remember { mutableStateOf<List<SubmissionInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSubmission by remember { mutableStateOf<SubmissionInfo?>(null) }

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
            println("[SubmissionScreen] Failed to load submissions: ${e.message}")
            error = "Failed to load submissions"
        }
        isLoading = false
    }

    if (selectedSubmission != null) {
        SubmissionCodeView(
            submission = selectedSubmission!!,
            onLoadIntoEditor = { code -> onLoadIntoEditor(code) },
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
    onLoadIntoEditor: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalEditorPalette.current

    // Surface (not a plain background modifier) so it also sets LocalContentColor to a
    // readable "on background" color for every unstyled Text/Icon below — same pattern as
    // OutputPanel.kt's root. A raw .background() only paints pixels, it doesn't fix text color.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        text = formatTimestamp(submission.timestamp),
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
                    onClick = { onLoadIntoEditor(submission.code) },
                    enabled = submission.code.isNotEmpty(),
                    modifier = Modifier.height(Dims.toolbarButtonHeight),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit in editor",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Edit in Editor", style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .verticalScroll(rememberScrollState())
            ) {
                if (submission.code.isEmpty()) {
                    Text(
                        text = "(no code available)",
                        style = MonoTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    val codeFieldState = remember(submission.code) { TextFieldState(submission.code) }
                    BasicTextField(
                        state = codeFieldState,
                        readOnly = true,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        textStyle = MonoTextStyle.copy(color = palette.consoleForeground),
                        cursorBrush = SolidColor(Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            // Same lockdown policy as the live code editor: recording the exact
                            // selection lets it be pasted back elsewhere in the app; anything not
                            // recorded this way is treated as an external paste and blocked.
                            .lockdownClipboardGuard {
                                val sel = codeFieldState.selection
                                if (sel.collapsed) null else codeFieldState.text.substring(sel.min, sel.max)
                            }
                    )
                }
            }
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
    // Surface, not a plain background modifier — see SubmissionCodeView for why.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
            Text(formatTimestamp(submission.timestamp), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
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
    val palette = LocalEditorPalette.current
    val color = when (status) {
        "AC"  -> palette.pass
        "WA"  -> palette.fail
        "TLE" -> palette.warning
        "CE"  -> palette.fail
        "RTE" -> palette.fail
        "MLE" -> palette.warning
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
