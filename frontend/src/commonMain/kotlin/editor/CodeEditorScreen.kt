@file:OptIn(ExperimentalFoundationApi::class)

package editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
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

@Composable
fun CodeEditorScreen(
    student: Student,
    problem: LabProblemInfo,
    backend: BackendService,
    repository: ProblemRepository,
    autosaveService: AutosaveService = NoOpAutosaveService,
    currentTheme: AppTheme = AppTheme.LIGHT,
    onThemeChange: (AppTheme) -> Unit = {},
    onSubmitExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val codeState = rememberTextFieldState("")
    // Use pre-initialized renderer from main() on desktop; create lazily on web (no JFXPanel issue)
    val htmlRenderer = LocalHtmlRenderer.current ?: remember { HtmlRenderer() }
    val state = remember(problem, backend, repository, scope) {
        CodeEditorState(problem, backend, repository, scope, codeState, student.email)
    }
    val problemPanelWidthState = remember { mutableStateOf(640.dp) }
    var problemPanelWidth by problemPanelWidthState

    // On open, repopulate the editor with the student's latest autosaved code (if any).
    // Keyed on the stable problem slug (not autosaveService, which is re-created on recomposition)
    // so this one-shot load isn't cancelled mid-flight. Guard against clobbering typed input.
    LaunchedEffect(problem.slug) {
        val saved = autosaveService.loadLatest()
        if (!saved.isNullOrEmpty() && codeState.text.isEmpty()) {
            codeState.setTextAndPlaceCursorAtEnd(saved)
        }
    }

    LaunchedEffect(autosaveService) {
        while (true) {
            delay(AUTOSAVE_INTERVAL_MS)
            val sessionValid = try {
                autosaveService.save(
                    code = codeState.text.toString(),
                    language = state.selectedLanguage
                )
            } catch (e: Exception) {
                println("[Autosave] save failed (loop continues): ${e.message}")
                true
            }
            if (!sessionValid) {
                println("[Autosave] session gone (401) — stopping autosave")
                break
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EditorTopBar(
            student = student,
            problemTitle = problem.title,
            isProblemPanelOpen = state.isProblemPanelOpen,
            onTogglePanel = { state.isProblemPanelOpen = !state.isProblemPanelOpen },
            currentTheme = currentTheme,
            onThemeChange = onThemeChange,
            onSubmitExit = {
                // Flush the final code before ending the lab, then exit (which stops lockdown
                // and commits the activity log incl. the LockdownEnded row).
                scope.launch {
                    try {
                        autosaveService.save(codeState.text.toString(), state.selectedLanguage)
                    } catch (e: Exception) {
                        println("[EndLab] autosave flush failed: ${e.message}")
                    }
                    onSubmitExit()
                }
            }
        )

        // Banner lives here — a Compose-only strip under the top bar that the HTML problem
        // panel never covers — so it's never occluded by the iframe/WebView (which paint above
        // Compose) and never overlaps the panel.
        LockdownBanner(LocalLockdown.current, Modifier.fillMaxWidth())

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
                ProblemPanelDivider(
                    renderer = htmlRenderer,
                    onDrag = { delta ->
                        problemPanelWidth = (problemPanelWidth + delta)
                            .coerceIn(PANEL_MIN_WIDTH, PANEL_MAX_WIDTH)
                    }
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CodeEditorPanel(
                    codeState = codeState,
                    selectedLanguage = state.selectedLanguage,
                    onTest = state::onTest,
                    onSubmit = state::onSubmit,
                    onClearOutput = state::onClearOutput,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                CustomInputPanel(
                    current = state.customInput,
                    onCurrentChange = { state.customInput = it },
                    cases = state.testCases,
                    onAddCase = {
                        if (state.testCases.size < maxCustomTestCases) {
                            state.testCases = state.testCases + state.customInput
                            state.customInput = ""
                        }
                    },
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

private const val AUTOSAVE_INTERVAL_MS = 60_000L
private val PANEL_MIN_WIDTH = 280.dp
private val PANEL_MAX_WIDTH = 760.dp

