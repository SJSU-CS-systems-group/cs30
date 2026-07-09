@file:OptIn(ExperimentalFoundationApi::class)

package editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import backend.BackendService
import data.LabProblemInfo
import data.ProblemRepository
import data.Student
import html.HtmlRenderer
import html.LocalHtmlRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lockdown.LocalLockdown
import lockdown.LockdownBanner
import theme.AppTheme
import theme.Dims


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
    val htmlRenderer = LocalHtmlRenderer.current ?: remember { HtmlRenderer() }
    val state = remember(problem, backend, repository, scope) {
        CodeEditorState(problem, backend, repository, scope, codeState, student.email)
    }
    var problemPanelFraction by remember { mutableStateOf(DEFAULT_PROBLEM_PANEL_FRACTION) }
    var outputPanelFraction by remember { mutableStateOf(DEFAULT_OUTPUT_PANEL_FRACTION) }

    LaunchedEffect(problem.slug) {
        val saved = autosaveService.loadLatest()
        if (!saved.isNullOrEmpty() && codeState.text.isEmpty()) {
            codeState.setTextAndPlaceCursorAtEnd(saved)
        }
    }

    LaunchedEffect(autosaveService) {
        while (true) {
            delay(AUTOSAVE_INTERVAL_MS)
            // Don't save empty code - this prevents overwriting saved code before loadLatest completes
            val code = codeState.text.toString()
            if (code.isNotEmpty()) {
                val sessionValid = try {
                    autosaveService.save(code, state.selectedLanguage)
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
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val sidebarWidth = if (state.isFocusMode) FOCUS_SIDEBAR_WIDTH else 0.dp
        val contentWidth = (screenWidth - sidebarWidth).coerceAtLeast(1.dp)
        val panelWidth = if (state.isProblemPanelOpen) contentWidth * problemPanelFraction else 0.dp
        val outputHeight = screenHeight * outputPanelFraction

        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                student = student,
                problemTitle = problem.title,
                isFocusMode = state.isFocusMode,
                onToggleFocusMode = state::onToggleFocusMode,
                currentTheme = currentTheme,
                onThemeChange = onThemeChange,
                onSubmitExit = {
                    scope.launch {
                        val code = codeState.text.toString()
                        if (code.isNotEmpty()) {
                            try {
                                autosaveService.save(code, state.selectedLanguage)
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
                if (state.isFocusMode) {
                    FocusSidebar(
                        isProblemPanelOpen = state.isProblemPanelOpen,
                        onToggleProblem = state::onToggleProblemPanel
                    )
                }

                Column(modifier = Modifier.width(panelWidth).fillMaxHeight()) {
                    ProblemPanel(
                        html = state.problemHtml,
                        css = state.problemCss,
                        renderer = htmlRenderer,
                        interactive = false,
                        isLoading = state.isLoading,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
                if (state.isProblemPanelOpen) {
                    ProblemPanelDivider(
                        renderer = htmlRenderer,
                        onDrag = { delta ->
                            val currentPanelWidth = contentWidth * problemPanelFraction
                            val newFraction = (currentPanelWidth + delta).value / contentWidth.value
                            problemPanelFraction = newFraction.coerceIn(MIN_PROBLEM_PANEL_FRACTION, MAX_PROBLEM_PANEL_FRACTION)
                        }
                    )
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CodeEditorPanel(
                        codeState = codeState,
                        selectedLanguage = state.selectedLanguage,
                        onTest = state::onTest,
                        onSubmit = state::onSubmit,
                        isOutputOpen = state.isOutputOpen,
                        onToggleOutput = state::onToggleOutput,
                        isBusy = state.isBusy,
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
                        onRemoveCase = { idx: Int -> state.testCases = state.testCases.filterIndexed { i, _ -> i != idx } },
                        isExpanded = state.isCustomInputExpanded,
                        onToggleExpanded = { state.isCustomInputExpanded = !state.isCustomInputExpanded },
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
                    onClose = state::onToggleOutput,
                    onDrag = { delta ->
                        val currentHeight = screenHeight * outputPanelFraction
                        val newFraction = (currentHeight - delta).value / screenHeight.value
                        outputPanelFraction = newFraction.coerceIn(MIN_OUTPUT_PANEL_FRACTION, MAX_OUTPUT_PANEL_FRACTION)
                    },
                    modifier = Modifier.fillMaxWidth().height(outputHeight)
                )
            }
        }
    }
}

/**
 * Holder for code editor state that persists across tab switches.
 * Used by UserScreen when tabs are shown.
 */
class CodeEditorStateHolder(
    val codeState: androidx.compose.foundation.text.input.TextFieldState,
    val state: CodeEditorState,
    var outputPanelHeight: androidx.compose.runtime.MutableState<Dp>
)

/**
 * Remember the code editor state across recompositions and tab switches.
 * Used by UserScreen when tabs are shown.
 */
@Composable
fun rememberCodeEditorState(
    student: Student,
    problem: LabProblemInfo,
    backend: BackendService,
    repository: ProblemRepository,
    autosaveService: AutosaveService,
    labTimeService: LabTimeService,
): CodeEditorStateHolder {
    val scope = rememberCoroutineScope()
    val codeState = rememberTextFieldState("")
    val state = remember(problem, backend, repository, scope) {
        CodeEditorState(problem, backend, repository, scope, codeState, student.email)
    }
    val outputPanelHeight = remember { mutableStateOf(Dims.outputPanelHeight) }

    LaunchedEffect(problem.slug) {
        val saved = autosaveService.loadLatest()
        if (!saved.isNullOrEmpty() && codeState.text.isEmpty()) {
            codeState.setTextAndPlaceCursorAtEnd(saved)
        }
    }

    LaunchedEffect(autosaveService, labTimeService) {
        while (true) {
            delay(AUTOSAVE_INTERVAL_MS)
            // Don't save empty code - this prevents overwriting saved code before loadLatest completes
            val code = codeState.text.toString()
            if (code.isNotEmpty()) {
                val sessionValid = try {
                    autosaveService.save(code, state.selectedLanguage)
                } catch (e: Exception) {
                    println("[Autosave] save failed: ${e.message}")
                    true
                }
                if (!sessionValid) {
                    println("[Autosave] session gone (401) — stopping autosave")
                    break
                }
            }
            state.labRemainingMs = try {
                labTimeService.fetchRemainingMs(state.problem.courseId, state.problem.labNumber)
            } catch (e: Exception) {
                println("[LabTime] Exception — stopping ${state.labRemainingMs}")
                state.labRemainingMs
            }
        }
    }

    return remember(state, codeState) {
        CodeEditorStateHolder(codeState, state, outputPanelHeight)
    }
}

/**
 * The right panel of the code editor (editor + custom input).
 * Used by UserScreen when tabs are shown.
 */
@Composable
fun CodeEditorRightPanel(
    editorState: CodeEditorStateHolder,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        CodeEditorPanel(
            codeState = editorState.codeState,
            selectedLanguage = editorState.state.selectedLanguage,
            onTest = editorState.state::onTest,
            onSubmit = editorState.state::onSubmit,
            isOutputOpen = editorState.state.isOutputOpen,
            onToggleOutput = editorState.state::onToggleOutput,
            isBusy = editorState.state.isBusy,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        CustomInputPanel(
            current = editorState.state.customInput,
            onCurrentChange = { editorState.state.customInput = it },
            cases = editorState.state.testCases,
            onAddCase = {
                if (editorState.state.testCases.size < maxCustomTestCases) {
                    editorState.state.testCases = editorState.state.testCases + editorState.state.customInput
                    editorState.state.customInput = ""
                }
            },
            onRemoveCase = { idx: Int -> editorState.state.testCases = editorState.state.testCases.filterIndexed { i, _ -> i != idx } },
            isExpanded = editorState.state.isCustomInputExpanded,
            onToggleExpanded = { editorState.state.isCustomInputExpanded = !editorState.state.isCustomInputExpanded },
        )
    }
}

/**
 * The output panel for the code editor.
 * Used by UserScreen when tabs are shown.
 */
@Composable
fun CodeEditorOutputPanel(
    editorState: CodeEditorStateHolder,
) {
    var outputPanelHeight by editorState.outputPanelHeight

    AnimatedVisibility(
        visible = editorState.state.isOutputOpen,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom)
    ) {
        OutputPanel(
            outputMode = editorState.state.outputMode,
            onClose = editorState.state::onToggleOutput,
            onDrag = { delta ->
                outputPanelHeight = (outputPanelHeight - delta)
                    .coerceIn(OUTPUT_PANEL_MIN_HEIGHT, OUTPUT_PANEL_MAX_HEIGHT)
            },
            modifier = Modifier.fillMaxWidth().height(outputPanelHeight)
        )
    }
}

@Composable
internal fun FocusSidebar(isProblemPanelOpen: Boolean, onToggleProblem: () -> Unit) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tabColor = if (isProblemPanelOpen) activeColor else idleColor
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    BoxWithConstraints(
        modifier = Modifier.width(FOCUS_SIDEBAR_WIDTH).fillMaxHeight()
    ) {
        val tabHeight = (maxHeight * FOCUS_SIDEBAR_TAB_FRACTION)
            .coerceIn(FOCUS_SIDEBAR_TAB_MIN_HEIGHT, FOCUS_SIDEBAR_TAB_MAX_HEIGHT)
        val spacerHeight = maxHeight * FOCUS_SIDEBAR_SPACER_FRACTION

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    drawLine(
                        color = borderColor,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(spacerHeight))
            val tabInteraction = remember { MutableInteractionSource() }
            val isHovered by tabInteraction.collectIsHoveredAsState()
            val tabBackground = when {
                isProblemPanelOpen -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                isHovered          -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                else               -> MaterialTheme.colorScheme.surface
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tabHeight)
                    .hoverable(tabInteraction)
                    .clickable(interactionSource = tabInteraction, indication = null, onClick = onToggleProblem)
                    .background(tabBackground),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Problem",
                    style = MaterialTheme.typography.labelSmall,
                    color = tabColor,
                    softWrap = false,
                    modifier = Modifier
                        .wrapContentWidth(unbounded = true)
                        .rotate(90f)
                )
            }
        }
    }
}

private const val AUTOSAVE_INTERVAL_MS = 60_000L
private const val DEFAULT_PROBLEM_PANEL_FRACTION = 0.35f
private const val MIN_PROBLEM_PANEL_FRACTION = 0.15f
private const val MAX_PROBLEM_PANEL_FRACTION = 0.70f
private const val DEFAULT_OUTPUT_PANEL_FRACTION = 0.25f
private const val MIN_OUTPUT_PANEL_FRACTION = 0.10f
private const val MAX_OUTPUT_PANEL_FRACTION = 0.50f
private val FOCUS_SIDEBAR_WIDTH = 25.dp
private const val FOCUS_SIDEBAR_TAB_FRACTION = 0.12f
private val FOCUS_SIDEBAR_TAB_MIN_HEIGHT = 60.dp
private val FOCUS_SIDEBAR_TAB_MAX_HEIGHT = 100.dp
private const val FOCUS_SIDEBAR_SPACER_FRACTION = 0.05f
private val OUTPUT_PANEL_MIN_HEIGHT = 120.dp
private val OUTPUT_PANEL_MAX_HEIGHT = 480.dp
