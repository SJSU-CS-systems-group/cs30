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
import labx.backend.HttpProblemRepository
import labx.data.ProblemRepository
import labx.data.ProblemSummary
import labx.data.Student
import labx.editor.AutosaveService
import labx.editor.CodeEditorScreen
import labx.editor.NoOpAutosaveService
import labx.editor.createAutosaveService
import labx.lockdown.CsvLockdownEventService
import labx.lockdown.DummyLockdownEventService
import labx.lockdown.LocalLockdown
import labx.lockdown.LockdownBanner
import labx.lockdown.LockdownEventService
import labx.lockdown.createActivityLogSessionHook
import labx.lockdown.rememberPlatformLockdownController
import labx.lockdown.defaultReporterBaseUrl
import labx.login.LoginScreen
import labx.problems.ProblemListScreen
import labx.start.StartLabScreen
import labx.theme.CS30Theme
import labx.theme.AppTheme

enum class Screen { Login, StartLab, ProblemList, Editor }

@Composable
fun App(initialStudent: Student? = null, bringToFront: () -> Unit = {}, onCloseApp: () -> Unit = {}) {
    val controller = rememberPlatformLockdownController()
    // TODO(real-backend): swap DummyBackendService for HttpBackendService(baseUrl).
    val backend: BackendService = remember { DummyBackendService() }
    val problemRepository: ProblemRepository = remember {
        HttpProblemRepository(defaultReporterBaseUrl)
    }
    // TODO(real-backend): swap DummyLockdownEventService for HttpLockdownEventService.
    var student by remember { mutableStateOf(initialStudent) }
    var screen by remember { mutableStateOf(if (initialStudent != null) Screen.StartLab else Screen.Login) }
    var selectedProblem by remember { mutableStateOf<ProblemSummary?>(null) }
    var studentEmail by remember { mutableStateOf("") }
    val lockdownEvents: LockdownEventService = remember(studentEmail) {
        if (studentEmail.isNotEmpty())
            CsvLockdownEventService(
                hook = createActivityLogSessionHook(defaultReporterBaseUrl),
                problemSlug = { selectedProblem?.slug }
            )
        else DummyLockdownEventService()
    }
    var theme by remember { mutableStateOf(AppTheme.LIGHT) }

    LaunchedEffect(lockdownEvents) { lockdownEvents.observe(controller) }

    CompositionLocalProvider(LocalLockdown provides controller) {
        CS30Theme(theme = theme) {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Login -> LoginScreen(
                        onLoginSuccess = { s ->
                            student = s
                            studentEmail = s.email
                            screen = Screen.StartLab
                        },
                        bringToFront = bringToFront,
                        onClose = onCloseApp
                    )
                    Screen.StartLab -> StartLabScreen(
                        studentName = student?.name ?: "",
                        onStart = { screen = Screen.ProblemList }
                    )
                    Screen.ProblemList -> ProblemListScreen(
                        studentName = student?.name ?: "",
                        repository = problemRepository,
                        onOpen = { p ->
                            selectedProblem = p
                            controller.start()
                            screen = Screen.Editor
                        },
                        onLogout = {
                            student = null
                            selectedProblem = null
                            screen = Screen.Login
                        },
                        onClose = onCloseApp
                    )
                    Screen.Editor -> CodeEditorScreen(
                        student = student!!,
                        problem = selectedProblem!!,
                        backend = backend,
                        repository = problemRepository,
                        autosaveService = if (studentEmail.isNotEmpty())
                            createAutosaveService(defaultReporterBaseUrl, selectedProblem!!.slug)
                        else NoOpAutosaveService,
                        currentTheme = theme,
                        onThemeChange = { theme = it },
                        onSubmitExit = {
                            controller.stop(onComplete = {
                                selectedProblem = null
                                screen = Screen.ProblemList
                            })
                        }
                    )
                }
                LockdownBanner(
                    controller = controller,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

