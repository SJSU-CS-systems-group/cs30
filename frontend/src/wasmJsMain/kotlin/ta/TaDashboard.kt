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
import auth.ApiToken
import auth.syncApiTokenToWindow
import backend.getCurrentAuthHeader
import clitoken.CliTokenBanner
import data.TaSectionInfo
import data.TaUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lockdown.defaultReporterBaseUrl

internal val TaGreen = Color(0xFF2E7D32)

private enum class DashboardScreen {
    SECTIONS, STUDENTS, LABS, ACTIVITY_LOG
}

@Composable
fun TaDashboard(ta: TaUser, onLogout: () -> Unit) {
    var currentScreen by remember { mutableStateOf(DashboardScreen.SECTIONS) }
    var selectedSection by remember { mutableStateOf<TaSectionInfo?>(null) }
    var selectedStudentEmail by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<TaSectionInfo>>(emptyList()) }

    val service = remember { HttpTaBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() } }

    // Fetches sections and refreshes selectedSection to match; shared by the poll loop below and
    // the manual refresh button so both go through the exact same refresh path.
    val refreshSections: suspend () -> Unit = {
        try {
            sections = service.getSections()
            // Update selected section if we have one
            if (selectedSection != null) {
                selectedSection = sections.find { it.courseId == selectedSection!!.courseId }
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    // Refresh sections data
    LaunchedEffect(Unit) {
        while (true) {
            refreshSections()
            delay(5000)
        }
    }

    val clearTokenAndLogout: () -> Unit = {
        ApiToken.value = null
        syncApiTokenToWindow(null)
        clearTaSessionFromStorage()
        onLogout()
    }

    // Heartbeat: keeps the server-side session alive every 5 minutes, and if the server reports
    // it already expired (30 min with no heartbeat - e.g. this tab was backgrounded or asleep),
    // kicks the TA back to the login screen instead of leaving a dead token behind. Checks
    // immediately on mount too, since a session restored from localStorage after a page refresh
    // (see main.kt) needs to be validated right away rather than trusted for a full interval.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                if (!service.checkSession().hasActiveSession) {
                    clearTokenAndLogout()
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                // Transient network failure - don't log out over it, just retry next heartbeat.
            }
            delay(5 * 60 * 1000)
        }
    }

    val handleBack: () -> Unit = {
        when (currentScreen) {
            DashboardScreen.ACTIVITY_LOG -> currentScreen = DashboardScreen.STUDENTS
            DashboardScreen.STUDENTS -> currentScreen = DashboardScreen.SECTIONS
            DashboardScreen.LABS -> currentScreen = DashboardScreen.SECTIONS
            DashboardScreen.SECTIONS -> { /* Already at root */ }
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
                    if (currentScreen != DashboardScreen.SECTIONS) {
                        IconButton(
                            onClick = handleBack,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(
                            when (currentScreen) {
                                DashboardScreen.SECTIONS -> "TA Dashboard"
                                DashboardScreen.STUDENTS -> "${selectedSection?.courseCode} Section ${selectedSection?.section}"
                                DashboardScreen.LABS -> "${selectedSection?.courseCode} Section ${selectedSection?.section}"
                                DashboardScreen.ACTIVITY_LOG -> "Activity Log"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            when (currentScreen) {
                                DashboardScreen.SECTIONS -> ta.name
                                DashboardScreen.STUDENTS -> "${selectedSection?.semester} ${selectedSection?.year}"
                                DashboardScreen.LABS -> "Labs"
                                DashboardScreen.ACTIVITY_LOG -> selectedStudentEmail ?: ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        CoroutineScope(Dispatchers.Default).launch {
                            service.logout()
                            clearTokenAndLogout()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Logout")
                }
            }
        }

        when (currentScreen) {
            DashboardScreen.SECTIONS -> {
                Column(modifier = Modifier.padding(16.dp)) {
                    CliTokenBanner(rawToken = ta.token, resetUrl = "/ta/login?reset=true", accentColor = TaGreen)
                }

                TaSectionsScreen(
                    sections = sections,
                    onStudentListClick = { section ->
                        selectedSection = section
                        currentScreen = DashboardScreen.STUDENTS
                    },
                    onLabListClick = { section ->
                        selectedSection = section
                        currentScreen = DashboardScreen.LABS
                    }
                )
            }
            DashboardScreen.STUDENTS -> {
                if (selectedSection != null) {
                    TaStudentsScreen(
                        section = selectedSection!!,
                        service = service,
                        onRefresh = { CoroutineScope(Dispatchers.Default).launch { refreshSections() } },
                        onViewActivityLog = { studentEmail ->
                            selectedStudentEmail = studentEmail
                            currentScreen = DashboardScreen.ACTIVITY_LOG
                        }
                    )
                }
            }
            DashboardScreen.LABS -> {
                if (selectedSection != null) {
                    TaLabsScreen(
                        section = selectedSection!!,
                        service = service
                    )
                }
            }
            DashboardScreen.ACTIVITY_LOG -> {
                if (selectedSection != null && selectedStudentEmail != null) {
                    TaActivityLogScreen(
                        studentEmail = selectedStudentEmail!!,
                        courseId = selectedSection!!.courseId,
                        service = service,
                        onBack = { currentScreen = DashboardScreen.STUDENTS }
                    )
                }
            }
        }
    }
}
