@file:OptIn(ExperimentalFoundationApi::class)

package labx.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import labx.backend.BackendService
import labx.data.ProblemSummary
import labx.data.Student
import labx.html.HtmlRenderer
import labx.html.LocalHtmlRenderer
import labx.theme.AppTheme

@Composable
fun CodeEditorScreen(
    student: Student,
    problem: ProblemSummary,
    backend: BackendService,
    currentTheme: AppTheme = AppTheme.LIGHT,
    onThemeChange: (AppTheme) -> Unit = {},
    onSubmitExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val codeState = rememberTextFieldState(STARTER_CODE.getValue(DEFAULT_LANGUAGE))
    // Use pre-initialized renderer from main() on desktop; create lazily on web (no JFXPanel issue)
    val htmlRenderer = LocalHtmlRenderer.current ?: remember { HtmlRenderer() }
    val state = remember(problem, backend, scope) {
        CodeEditorState(problem, backend, scope, codeState)
    }
    val problemPanelWidthState = remember { mutableStateOf(320.dp) }
    var problemPanelWidth by problemPanelWidthState

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            student = student,
            problemTitle = problem.title,
            isProblemPanelOpen = state.isProblemPanelOpen,
            onTogglePanel = { state.isProblemPanelOpen = !state.isProblemPanelOpen },
            currentTheme = currentTheme,
            onThemeChange = onThemeChange,
            onSubmitExit = onSubmitExit
        )

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.isProblemPanelOpen) {
                println("[CodeEditorScreen] 📌 Problem panel open")
                ProblemPanel(
                    html = state.problemHtml,
                    css = state.problemCss,
                    renderer = htmlRenderer,
                    interactive = false,
                    isLoading = state.isLoading,
                    modifier = Modifier.width(problemPanelWidth)
                )
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                        .resizeCursorModifier()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                problemPanelWidth = (problemPanelWidth + dragAmount.toDp())
                                    .coerceIn(200.dp, 600.dp)
                            }
                        }
                        .background(MaterialTheme.colorScheme.outline)
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CodeEditorPanel(
                    codeState = codeState,
                    selectedLanguage = state.selectedLanguage,
                    onLanguageChange = state::onLanguageChange,
                    onRun = state::onRun,
                    onTest = state::onTest,
                    onSubmit = state::onSubmit,
                    onClearOutput = state::onClearOutput,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                CustomInputPanel(
                    current = state.customInput,
                    onCurrentChange = { state.customInput = it },
                    cases = state.testCases,
                    onAddCase = { state.testCases = state.testCases + state.customInput; state.customInput = "" },
                    onRemoveCase = { idx: Int -> state.testCases = state.testCases.filterIndexed { i, _ -> i != idx } }
                )
            }
        }

        AnimatedVisibility(
            visible = state.isOutputOpen,
            enter = expandVertically(expandFrom = Alignment.Bottom),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            OutputPanel(
                outputMode = state.outputMode,
                onClose = state::onToggleOutput
            )
        }
    }
}
