package ta

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import backend.getCurrentAuthHeader
import data.TaSectionInfo
import data.TaUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lockdown.defaultReporterBaseUrl

internal val TaGreen = Color(0xFF2E7D32)

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
            TaSectionsScreen(
                sections = sections,
                onSectionClick = { section ->
                    selectedSection = section
                    showStudents = true
                }
            )
        } else if (selectedSection != null) {
            TaStudentsScreen(
                section = selectedSection!!,
                service = service
            )
        }
    }
}
