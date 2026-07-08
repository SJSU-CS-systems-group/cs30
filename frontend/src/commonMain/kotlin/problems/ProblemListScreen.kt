package problems

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.LabProblemInfo
import data.ProblemRepository
import editor.AppTopBar
import lockdown.LocalLockdown
import lockdown.LockdownBanner

@Composable
fun ProblemListScreen(
    studentName: String,
    repository: ProblemRepository,
    onOpen: (LabProblemInfo) -> Unit,
    onLogout: () -> Unit,
) {
    var problems by remember { mutableStateOf<List<LabProblemInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            problems = repository.listProblemsForStudent()
            errorMessage = null
        } catch (e: NoSuchElementException) {
            errorMessage = "NOT_ENROLLED"
        } catch (e: Exception) {
            errorMessage = "Failed to load problems: ${e.message}"
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = "CS30",
            subtitle = "Problems",
            studentName = studentName,
            onLogout = onLogout
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Lockdown is active on this screen; show violation banners in a Compose strip under the top bar.
        LockdownBanner(LocalLockdown.current, Modifier.fillMaxWidth())

        Text(
            text = "Select a problem to begin.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 12.dp),
                        tint = if (errorMessage == "NOT_ENROLLED") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = if (errorMessage == "NOT_ENROLLED") "No lab has been assigned to you" else errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (errorMessage == "NOT_ENROLLED") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                    if (errorMessage == "NOT_ENROLLED") {
                        Text(
                            text = "Contact your instructor to enroll in a lab",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        } else if (problems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "No active labs right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Check back during your scheduled lab time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(problems, key = { "${it.courseId}-${it.section}-${it.labNumber}-${it.slug}" }) { p ->
                    ProblemRow(p, onOpen = { onOpen(p) })
                }
            }
        }
    }
}

@Composable
private fun ProblemRow(problem: LabProblemInfo, onOpen: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = problem.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = "${problem.courseCode} - Section ${problem.section}, Lab ${problem.labNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("Open") }
        }
    }
}
