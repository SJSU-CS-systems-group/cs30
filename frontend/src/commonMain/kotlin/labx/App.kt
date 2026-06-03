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
import labx.data.MockDataRepository
import labx.data.ProblemSummary
import labx.data.Student
import labx.editor.CodeEditorScreen
import labx.lockdown.DummyLockdownEventService
import labx.lockdown.LocalLockdown
import labx.lockdown.LockdownBanner
import labx.lockdown.LockdownEventService
import labx.lockdown.rememberPlatformLockdownController
import labx.login.LoginScreen
import labx.problems.ProblemListScreen
import labx.theme.CS30Theme

enum class Screen { Login, StartLab, ProblemList, Editor }

@Composable
fun App(initialStudent: Student? = null) {
    val controller = rememberPlatformLockdownController()
    // TODO(real-backend): swap DummyBackendService for HttpBackendService(baseUrl).
    val backend: BackendService = remember { DummyBackendService() }
    // TODO(real-backend): swap DummyLockdownEventService for HttpLockdownEventService.
    val lockdownEvents: LockdownEventService = remember { DummyLockdownEventService() }
    var student by remember { mutableStateOf(initialStudent) }
    var screen by remember { mutableStateOf(if (initialStudent != null) Screen.StartLab else Screen.Login) }
    var selectedProblem by remember { mutableStateOf<ProblemSummary?>(null) }

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
                        onStart = { screen = Screen.ProblemList }
                    )
                    Screen.ProblemList -> ProblemListScreen(
                        studentName = student?.name ?: "",
                        repository = MockDataRepository,
                        onOpen = { p ->
                            selectedProblem = p
                            controller.start()
                            screen = Screen.Editor
                        },
                        onLogout = {
                            student = null
                            selectedProblem = null
                            screen = Screen.Login
                        }
                    )
                    Screen.Editor -> CodeEditorScreen(
                        student = student!!,
                        problem = selectedProblem!!,
                        backend = backend,
                        onSubmitExit = {
                            controller.stop()
                            selectedProblem = null
                            screen = Screen.ProblemList
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

