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
    var showStudents by remember { mutableStateOf(false) }
    var selectedSection by remember { mutableStateOf<TaSectionInfo?>(null) }
    var sections by remember { mutableStateOf<List<TaSectionInfo>>(emptyList()) }

    val service = remember { HttpTaBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() } }

    // Refresh sections data
    LaunchedEffect(Unit) {
        while (true) {
            try {
                sections = service.getSections()
                // Update selected section if we have one
                if (selectedSection != null) {
                    selectedSection = sections.find { it.courseId == selectedSection!!.courseId }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
            delay(5000)
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showStudents) {
                        IconButton(
                            onClick = { showStudents = false },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(
                            if (!showStudents) "TA Dashboard" else "${selectedSection?.courseCode} Section ${selectedSection?.section}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            if (!showStudents) ta.name else "${selectedSection?.semester} ${selectedSection?.year}",
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

        if (!showStudents) {
            SectionsScreen(
                sections = sections,
                onSectionClick = { section ->
                    selectedSection = section
                    showStudents = true
                }
            )
        } else if (selectedSection != null) {
            SectionStudentsScreen(
                section = selectedSection!!,
                service = service
            )
        }
    }
}

@Composable
private fun SectionsScreen(
    sections: List<TaSectionInfo>,
    onSectionClick: (TaSectionInfo) -> Unit
) {
    val totalStudents = sections.sumOf { it.students.size }
    val activeStudents = sections.sumOf { section -> section.students.count { it.status == "active" } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = "Total Students",
                value = totalStudents.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Active Now",
                value = activeStudents.toString(),
                color = if (activeStudents > 0) TaGreen else null,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            "Your Sections",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        if (sections.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    "No sections found",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sections) { section ->
                    SectionCard(section = section, onClick = { onSectionClick(section) })
                }
            }
        }
    }
}

@Composable
private fun SectionStudentsScreen(
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
                    if (isActive) "On" else "Off",
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
private fun SectionCard(
    section: TaSectionInfo,
    onClick: () -> Unit
) {
    val activeCount = section.students.count { it.status == "active" }

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
                    "${section.courseCode} - Section ${section.section}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${section.semester} ${section.year}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$activeCount active",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (activeCount > 0) TaGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${section.students.size} students",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
