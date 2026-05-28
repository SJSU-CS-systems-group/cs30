package labx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import labx.backend.BackendService
import labx.backend.DummyBackendService
import labx.data.Student
import labx.editor.CodeEditorScreen
import labx.lockdown.DummyLockdownEventService
import labx.lockdown.LocalLockdown
import labx.lockdown.LockdownBanner
import labx.lockdown.LockdownEventService
import labx.lockdown.rememberPlatformLockdownController
import labx.login.LoginScreen
import labx.theme.CS30Theme

enum class Screen { Login, StartLab, Editor }

@Composable
fun App(initialStudent: Student? = null) {
    val controller = rememberPlatformLockdownController()
    // TODO(real-backend): swap DummyBackendService for HttpBackendService(baseUrl).
    val backend: BackendService = remember { DummyBackendService() }
    // TODO(real-backend): swap DummyLockdownEventService for HttpLockdownEventService.
    val lockdownEvents: LockdownEventService = remember { DummyLockdownEventService() }
    var student by remember { mutableStateOf(initialStudent) }
    var screen by remember { mutableStateOf(if (initialStudent != null) Screen.StartLab else Screen.Login) }

    LaunchedEffect(controller) { lockdownEvents.observe(controller) }

    CompositionLocalProvider(LocalLockdown provides controller) {
        CS30Theme {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Login -> LoginScreen(
                        onLoginSuccess = { s ->
                            student = s
                            screen = Screen.StartLab
                        }
                    )
                    Screen.StartLab -> StartLabScreen(
                        studentName = student?.name ?: "",
                        onStart = {
                            controller.start()
                            screen = Screen.Editor
                        }
                    )
                    Screen.Editor -> CodeEditorScreen(
                        student = student!!,
                        backend = backend,
                        onSubmitExit = {
                            controller.stop()
                            student = null
                            screen = Screen.Login
                        }
                    )
                }
                LockdownBanner(
                    controller = controller,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun StartLabScreen(studentName: String, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (studentName.isNotBlank()) "Welcome, $studentName" else "Welcome",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Click Start Lab to enter lockdown mode.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Tab switching, paste from outside, and right-click are disabled during the lab.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onStart, modifier = Modifier.padding(8.dp)) {
                Text("Start Lab", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
