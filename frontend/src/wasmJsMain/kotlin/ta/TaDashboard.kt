package ta

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import backend.getCurrentAuthHeader
import data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lockdown.defaultReporterBaseUrl

private val TaGreen = Color(0xFF2E7D32)

@Composable
fun TaDashboard(ta: TaUser, onLogout: () -> Unit) {
    var sections by remember { mutableStateOf<List<TaSectionInfo>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<TaSessionInfo>>(emptyList()) }
    var stats by remember { mutableStateOf<TaDashboardStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showKickDialog by remember { mutableStateOf<TaSessionInfo?>(null) }

    val service = remember { HttpTaBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() } }

    // Auto-refresh every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            try {
                sections = service.getSections()
                sessions = service.getActiveSessions()
                stats = service.getStats()
                isLoading = false
                error = null
            } catch (e: Exception) {
                error = "Failed to load data: ${e.message}"
                isLoading = false
            }
            delay(5000)
        }
    }

    // Kick dialog
    if (showKickDialog != null) {
        AlertDialog(
            onDismissRequest = { showKickDialog = null },
            title = { Text("Kick Student") },
            text = { Text("Are you sure you want to end the session for ${showKickDialog!!.studentEmail}?") },
            confirmButton = {
                Button(
                    onClick = {
                        val session = showKickDialog!!
                        showKickDialog = null
                        // Kick will happen and refresh will pick it up
                        CoroutineScope(Dispatchers.Default).launch {
                            service.kickStudent(session.token)
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TaGreen,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "TA Dashboard",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        ta.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        CoroutineScope(Dispatchers.Default).launch {
                            service.logout()
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Logout")
                }
            }
        }

        if (isLoading && sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left panel - Stats & Sections
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Stats cards
                    if (stats != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            StatCard(
                                title = "Total Students",
                                value = stats!!.totalStudents.toString(),
                                modifier = Modifier.weight(1f)
                            )
                            StatCard(
                                title = "Active Now",
                                value = stats!!.activeStudents.toString(),
                                color = if (stats!!.activeStudents > 0) TaGreen else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Sections list
                    Text(
                        "Your Sections",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sections) { section ->
                            SectionCard(section)
                        }
                    }
                }

                // Right panel - Active Sessions
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Active Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
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

                    if (sessions.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                "No active sessions",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sessions) { session ->
                                SessionCard(
                                    session = session,
                                    onKick = { showKickDialog = session }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (error != null) {
            Snackbar(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(error!!)
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    color: Color? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color ?: MaterialTheme.colorScheme.onSurface
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionCard(section: TaSectionInfo) {
    val activeCount = section.students.count { it.hasActiveSession }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${section.courseCode} - Section ${section.section}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "$activeCount / ${section.students.size} active",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activeCount > 0) TaGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${section.semester} ${section.year}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionCard(session: TaSessionInfo, onKick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = TaGreen
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        session.studentEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${session.platform} · ${session.ipAddress}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onKick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Kick")
            }
        }
    }
}
