package editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import backend.BackendService
import data.LabProblemInfo
import data.ProblemRepository
import data.Student
import html.HtmlRenderer
import html.LocalHtmlRenderer
import kotlinx.coroutines.launch
import lockdown.LocalLockdown
import lockdown.LockdownBanner
import theme.AppTheme

enum class Tab {
    CODE_EDITOR, SUBMISSIONS
}

@Composable
fun UserScreen(
    student: Student,
    problem: LabProblemInfo,
    backend: BackendService,
    repository: ProblemRepository,
    autosaveService: AutosaveService = NoOpAutosaveService,
    labTimeService: LabTimeService,
    currentTheme: AppTheme = AppTheme.LIGHT,
    onThemeChange: (AppTheme) -> Unit = {},
    onSubmitExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(Tab.CODE_EDITOR) }
    var problemPanelWidth by remember { mutableStateOf(640.dp) }
    val htmlRenderer = LocalHtmlRenderer.current ?: remember { HtmlRenderer() }

    // State for the code editor (needs to persist across tab switches)
    val editorState = rememberCodeEditorState(
        student = student,
        problem = problem,
        backend = backend,
        repository = repository,
        autosaveService = autosaveService,
        labTimeService = labTimeService,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        val isFocusMode = editorState.state.isFocusMode

        EditorTopBar(
            student = student,
            problemTitle = problem.title,
            problemNote = problem.note,
            remainingMs = editorState.state.labRemainingMs,
            isFocusMode = isFocusMode,
            onToggleFocusMode = editorState.state::onToggleFocusMode,
            currentTheme = currentTheme,
            onThemeChange = onThemeChange,
            onSubmitExit = {
                scope.launch {
                    val code = editorState.codeState.text.toString()
                    if (code.isNotEmpty()) {
                        try {
                            autosaveService.save(code, editorState.state.selectedLanguage)
                            println("[EndLab] autosave saved before exit")
                        } catch (e: Exception) {
                            println("[EndLab] autosave flush failed: ${e.message}")
                        }
                    }
                    onSubmitExit()
                }
            }
        )

        LockdownBanner(LocalLockdown.current, Modifier.fillMaxWidth())

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isFocusMode) {
                FocusSidebar(
                    isProblemPanelOpen = editorState.state.isProblemPanelOpen,
                    onToggleProblem = editorState.state::onToggleProblemPanel
                )
            }

            if (editorState.state.isProblemPanelOpen) {
                ProblemPanel(
                    html = editorState.state.problemHtml,
                    css = editorState.state.problemCss,
                    renderer = htmlRenderer,
                    interactive = false,
                    isLoading = editorState.state.isLoading,
                    modifier = Modifier.width(problemPanelWidth)
                )
                ProblemPanelDivider(
                    renderer = htmlRenderer,
                    onDrag = { delta ->
                        problemPanelWidth = (problemPanelWidth + delta)
                            .coerceIn(280.dp, 760.dp)
                    }
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TabRow(selectedTabIndex = Tab.entries.indexOf(selectedTab)) {
                    Tab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.name.replace("_", " ")) }
                        )
                    }
                }

                when (selectedTab) {
                    Tab.CODE_EDITOR -> CodeEditorRightPanel(
                        editorState = editorState,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    Tab.SUBMISSIONS -> SubmissionScreen(
                        problem = problem,
                        studentEmail = student.email,
                        backend = backend,
                        onLoadIntoEditor = { code ->
                            editorState.codeState.edit {
                                replace(0, length, code)
                                selection = TextRange(code.length)
                            }
                            selectedTab = Tab.CODE_EDITOR
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }

        // Output panel at bottom (only visible when code editor tab and output is open)
        if (selectedTab == Tab.CODE_EDITOR) {
            CodeEditorOutputPanel(editorState = editorState)
        }
    }
}