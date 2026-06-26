package app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import backend.BackendService
import backend.HttpBackendService
import backend.HttpProblemRepository
import backend.getCurrentAuthHeader
import data.LabProblemInfo
import data.ProblemRepository
import data.Student
import editor.CodeEditorScreen
import editor.NoOpAutosaveService
import editor.createAutosaveService
import lockdown.CsvLockdownEventService
import lockdown.DummyLockdownEventService
import lockdown.LocalLockdown
import lockdown.LockdownEventService
import lockdown.createActivityLogSessionHook
import lockdown.rememberPlatformLockdownController
import lockdown.defaultReporterBaseUrl
import auth.createAuthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import login.LoginScreen
import problems.ProblemListScreen
import start.StartLabScreen
import theme.CS30Theme
import theme.AppTheme

enum class Screen { Login, StartLab, ProblemList, Editor }

@Composable
fun App(initialStudent: Student? = null, bringToFront: () -> Unit = {}, onCloseApp: () -> Unit = {}) {
    val controller = rememberPlatformLockdownController()
    val backend: BackendService = remember {
        HttpBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() }
    }
    val problemRepository: ProblemRepository = remember {
        HttpProblemRepository(defaultReporterBaseUrl) { getCurrentAuthHeader() }
    }
    val authService = remember { createAuthService() }
    // TODO(real-backend): swap DummyLockdownEventService for HttpLockdownEventService.
    var student by remember { mutableStateOf(initialStudent) }
    var screen by remember { mutableStateOf(if (initialStudent != null) Screen.StartLab else Screen.Login) }
    var selectedProblem by remember { mutableStateOf<LabProblemInfo?>(null) }
    // Initialize from the injected student so the web OAuth path (which arrives via URL
    // params as initialStudent and skips the Login screen) also populates the email — without
    // it, web falls back to Dummy/NoOp services and never calls the backend.
    var studentEmail by remember { mutableStateOf(initialStudent?.email ?: "") }
    val lockdownEvents: LockdownEventService = remember(studentEmail) {
        if (studentEmail.isNotEmpty()) {
            println("[App] lockdown service = CsvLockdownEventService (Http) for email='$studentEmail'")
            CsvLockdownEventService(
                hook = createActivityLogSessionHook(defaultReporterBaseUrl),
                problemSlug = { selectedProblem?.slug }
            )
        } else {
            println("[App] lockdown service = DummyLockdownEventService (no studentEmail)")
            DummyLockdownEventService()
        }
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
                        onStart = {
                            screen = Screen.ProblemList
                            controller.start()  // TODO: disable for testing
                        }
                    )
                    Screen.ProblemList -> ProblemListScreen(
                        studentName = student?.name ?: "",
                        repository = problemRepository,
                        onOpen = { p ->
                            selectedProblem = p
                            screen = Screen.Editor
                        },
                        onLogout = {
                            controller.stop(onComplete = {
                                CoroutineScope(Dispatchers.Default).launch {
                                    authService.logout()
                                }
                                student = null
                                studentEmail = ""
                                selectedProblem = null
                                screen = Screen.Login
                            })
                        },
                        onClose = {
                            controller.stop(onComplete = onCloseApp)
                        }
                    )
                    Screen.Editor -> CodeEditorScreen(
                        student = student!!,
                        problem = selectedProblem!!,
                        backend = backend,
                        repository = problemRepository,
                        autosaveService = if (studentEmail.isNotEmpty()) {
                            println("[App] autosave service = HttpAutosaveService for email='$studentEmail' slug='${selectedProblem!!.slug}'")
                            createAutosaveService(defaultReporterBaseUrl, selectedProblem!!)
                        } else {
                            println("[App] autosave service = NoOpAutosaveService (no studentEmail)")
                            NoOpAutosaveService
                        },
                        currentTheme = theme,
                        onThemeChange = { theme = it },
                        onSubmitExit = {
                            selectedProblem = null
                            screen = Screen.ProblemList
                        }
                    )
                }
            }
        }
    }
}

