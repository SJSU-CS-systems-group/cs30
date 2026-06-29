package editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import backend.BackendService
import data.LabProblemInfo
import data.ProblemRepository
import data.Student
import html.HtmlRenderer
import html.LocalHtmlRenderer
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
    currentTheme: AppTheme = AppTheme.LIGHT,
    onThemeChange: (AppTheme) -> Unit = {},
    onSubmitExit: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(Tab.CODE_EDITOR) }
    var problemPanelWidth by remember { mutableStateOf(640.dp) }
    val htmlRenderer = LocalHtmlRenderer.current ?: remember { HtmlRenderer() }

    // State for the code editor (needs to persist across tab switches)
    val editorState = rememberCodeEditorState(
        student = student,
        problem = problem,
        backend = backend,
        repository = repository,
        autosaveService = autosaveService
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar - always visible (no focus mode in tab view)
        EditorTopBar(
            student = student,
            problemTitle = problem.title,
            isFocusMode = false,
            onToggleFocusMode = { },
            currentTheme = currentTheme,
            onThemeChange = onThemeChange,
            onSubmitExit = onSubmitExit
        )

        // Lockdown banner - always visible
        LockdownBanner(LocalLockdown.current, Modifier.fillMaxWidth())

        // Main content area with problem panel on left, tabs+content on right
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Problem panel - always visible on left (doesn't change with tabs)
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

            // Right side: tabs + content
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Tab bar - just above the code editor
                TabRow(selectedTabIndex = Tab.entries.indexOf(selectedTab)) {
                    Tab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.name.replace("_", " ")) }
                        )
                    }
                }

                // Content area based on selected tab
                when (selectedTab) {
                    Tab.CODE_EDITOR -> CodeEditorRightPanel(
                        editorState = editorState,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    Tab.SUBMISSIONS -> SubmissionScreen(
                        problem = problem,
                        studentEmail = student.email,
                        backend = backend,
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