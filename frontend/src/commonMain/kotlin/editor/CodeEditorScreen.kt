@file:OptIn(ExperimentalFoundationApi::class)

package editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints


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
    var problemPanelFraction by remember { mutableStateOf(DEFAULT_PROBLEM_PANEL_FRACTION) }
    var outputPanelFraction by remember { mutableStateOf(DEFAULT_OUTPUT_PANEL_FRACTION) }

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val screenWidth = maxWidth
    val screenHeight = maxHeight

    Column(modifier = Modifier.fillMaxSize()) {
        EditorTopBar(
            student = student,
            problemTitle = problem.title,
            isFocusMode = state.isFocusMode,
            onToggleFocusMode = state::onToggleFocusMode,
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

        val sidebarWidth = if (state.isFocusMode) FOCUS_SIDEBAR_WIDTH else 0.dp
        val contentWidth = (screenWidth - sidebarWidth).coerceAtLeast(1.dp)
        val panelWidth = if (state.isProblemPanelOpen) contentWidth * problemPanelFraction else 0.dp
        val outputHeight = screenHeight * outputPanelFraction

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Focus mode: always-visible sidebar for panel navigation.
            if (state.isFocusMode) {
                FocusSidebar(
                    isProblemPanelOpen = state.isProblemPanelOpen,
                    onToggleProblem = { state.isProblemPanelOpen = !state.isProblemPanelOpen }
                )
            }

            // Single ProblemPanel always in the composition — width=0 hides it without
            // destroying the iframe/WebView.
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
                        val newFraction = (panelWidth + delta).value / contentWidth.value
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
                    val newFraction = (outputHeight - delta).value / screenHeight.value
                    outputPanelFraction = newFraction.coerceIn(MIN_OUTPUT_PANEL_FRACTION, MAX_OUTPUT_PANEL_FRACTION)
                },
                modifier = Modifier.fillMaxWidth().height(outputHeight)
            )
        }
    }
    } // end BoxWithConstraints
}

@Composable
private fun FocusSidebar(isProblemPanelOpen: Boolean, onToggleProblem: () -> Unit) {
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
// Tab occupies ~12% of sidebar height, clamped so it always fits the rotated "Problem" label.
private const val FOCUS_SIDEBAR_TAB_FRACTION = 0.12f
private val FOCUS_SIDEBAR_TAB_MIN_HEIGHT = 60.dp
private val FOCUS_SIDEBAR_TAB_MAX_HEIGHT = 100.dp
// Gap above the first tab: ~5% of sidebar height.
private const val FOCUS_SIDEBAR_SPACER_FRACTION = 0.05f

