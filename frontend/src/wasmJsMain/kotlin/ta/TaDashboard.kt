package ta

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
    var showLabStudents by remember { mutableStateOf(false) }
    var selectedLab by remember { mutableStateOf<TaLabInfo?>(null) }

    val service = remember { HttpTaBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() } }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showLabStudents) {
                        IconButton(
                            onClick = { showLabStudents = false },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(
                            if (!showLabStudents) "TA Dashboard" else "Lab ${selectedLab?.labNumber}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            if (!showLabStudents) ta.name else "${selectedLab?.courseCode} Section ${selectedLab?.section}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        CoroutineScope(Dispatchers.Default).launch {
                            service.logout()
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Logout")
                }
            }
        }

        if (!showLabStudents) {
            LabsScreen(
                service = service,
                onLabClick = { lab ->
                    selectedLab = lab
                    showLabStudents = true
                }
            )
        } else {
            LabStudentsScreen(
                service = service,
                lab = selectedLab!!
            )
        }
    }
}

@Composable
private fun LabsScreen(
    service: TaBackendService,
    onLabClick: (TaLabInfo) -> Unit
) {
    var labs by remember { mutableStateOf<List<TaLabInfo>>(emptyList()) }
    var stats by remember { mutableStateOf<TaDashboardStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                labs = service.getLabs()
                stats = service.getStats()
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
            delay(10000)
        }
    }

    if (isLoading && labs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TaGreen)
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Stats row
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

            Text(
                "Labs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (labs.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        "No labs found",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(labs) { lab ->
                        LabCard(lab = lab, onClick = { onLabClick(lab) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LabStudentsScreen(
    service: TaBackendService,
    lab: TaLabInfo
) {
    var students by remember { mutableStateOf<List<TaSessionInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showKickDialog by remember { mutableStateOf<TaSessionInfo?>(null) }

    LaunchedEffect(lab.labId) {
        while (true) {
            try {
                students = service.getLabStudents(lab.labId)
                isLoading = false
            } catch (e: Exception) {
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

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lab.isActive) {
                    Surface(
                        color = TaGreen,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "ACTIVE",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    "${students.size} active session${if (students.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

        if (isLoading && students.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TaGreen)
            }
        } else if (students.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    "No active sessions",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
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
                                modifier = Modifier.weight(2f)
                            )
                            Text(
                                "IP Address",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Platform",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Box(modifier = Modifier.width(60.dp))
                        }
                    }
                    HorizontalDivider()

                    LazyColumn {
                        items(students) { session ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    session.studentEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(2f)
                                )
                                Text(
                                    session.ipAddress,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    session.platform,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { showKickDialog = session },
                                    modifier = Modifier.width(60.dp),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Kick")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
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
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = color ?: MaterialTheme.colorScheme.onSurface
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LabCard(
    lab: TaLabInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Lab ${lab.labNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${lab.courseCode} - Section ${lab.section}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (lab.isActive) {
                Surface(
                    color = TaGreen,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "ACTIVE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
