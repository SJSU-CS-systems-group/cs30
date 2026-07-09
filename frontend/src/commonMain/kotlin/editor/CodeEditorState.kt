package editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import backend.BackendService
import backend.SubmitRequest
import backend.TestRequest
import data.LabProblemInfo
import data.ProblemRepository
import data.RuntimeError

@Stable
class CodeEditorState(
    val problem: LabProblemInfo,
    val backend: BackendService,
    val repository: ProblemRepository,
    val scope: CoroutineScope,
    val codeState: TextFieldState,
    val studentEmail: String,
) {
    private val _problemHtml = mutableStateOf("")
    private val _problemCss = mutableStateOf("")
    private val _isLoading = mutableStateOf(true)
    // Locked to the problem's language — students cannot switch (judge only accepts the
    // course/problem language). Sourced from LabProblemInfo.language.
    private val _selectedLanguage = mutableStateOf(problem.language)
    private val _customInput = mutableStateOf("")
    private val _testCases = mutableStateOf<List<String>>(emptyList())
    private val _outputMode = mutableStateOf<OutputMode>(OutputMode.Empty)
    private val _isOutputOpen = mutableStateOf(false)
    private val _isProblemPanelOpen = mutableStateOf(true)
    private val _isCustomInputExpanded = mutableStateOf(true)
    private val _isFocusMode = mutableStateOf(false)
    private val _editorFontSize = mutableStateOf(EDITOR_DEFAULT_FONT_SIZE)
    private val _labRemainingMs = mutableStateOf<Long?>(null)
    private val _isBusy = mutableStateOf(false)

    var problemHtml by _problemHtml
    var problemCss by _problemCss
    var isLoading by _isLoading
    var selectedLanguage by _selectedLanguage
    var customInput by _customInput
    var testCases by _testCases
    var outputMode by _outputMode
    var isOutputOpen by _isOutputOpen
    var isProblemPanelOpen by _isProblemPanelOpen
    var isCustomInputExpanded by _isCustomInputExpanded
    var isFocusMode by _isFocusMode
    var editorFontSize by _editorFontSize
    var labRemainingMs by _labRemainingMs
    var isBusy by _isBusy

    init {
        println("[CodeEditorState] Init: loading problem ${problem.slug}")
        load()
    }

    private fun load() {
        scope.launch {
            println("[CodeEditorState] Loading content for ${problem.slug}")
            isLoading = true
            val content = repository.getProblemContent(
                problem.courseId,
                problem.section,
                problem.labNumber,
                problem.slug
            )
            problemHtml = content.html
            problemCss = content.css
            isLoading = false
            println("[CodeEditorState] Content loaded (html: ${content.html.length} bytes, css: ${content.css.length} bytes)")
        }
    }

    fun onTest() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            try {
                println("[CodeEditorState] 🧪 Testing code (${selectedLanguage})")
                isOutputOpen = true
                outputMode = OutputMode.Loading
                // Run the queued cases; if none queued, fall back to the input box as a single quick case.
                val customs = testCases.ifEmpty { if (customInput.isNotBlank()) listOf(customInput) else emptyList() }
                outputMode = try {
                    val response = backend.testCode(
                        TestRequest(
                            courseId = problem.courseId,
                            section = problem.section,
                            labNumber = problem.labNumber,
                            problemName = problem.slug,
                            studentEmail = studentEmail,
                            language = selectedLanguage,
                            code = codeState.text.toString(),
                            customStdins = customs,
                        )
                    )
                    terminalErrorOrNull(response) ?: OutputMode.Test(response, isSubmit = false)
                } catch (e: Exception) {
                    println("[CodeEditorState] onTest failed: ${e.message}")
                    OutputMode.Error(RuntimeError("ERROR", "Run failed"))
                }
                println("[CodeEditorState] ✅ Test complete")
            } finally {
                isBusy = false
            }
        }
    }

    fun onSubmit() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            try {
                println("[CodeEditorState] ✔️ Submitting code (${selectedLanguage})")
                isOutputOpen = true
                outputMode = OutputMode.Loading
                outputMode = try {
                    val response = backend.submitCode(
                        SubmitRequest(
                            courseId = problem.courseId,
                            section = problem.section,
                            labNumber = problem.labNumber,
                            problemName = problem.slug,
                            studentEmail = studentEmail,
                            language = selectedLanguage,
                            code = codeState.text.toString(),
                        )
                    ).response
                    terminalErrorOrNull(response) ?: OutputMode.Test(response, isSubmit = true)
                } catch (e: Exception) {
                    println("[CodeEditorState] onSubmit failed: ${e.message}")
                    OutputMode.Error(RuntimeError("ERROR", "Submit failed"))
                }
                println("[CodeEditorState] ✅ Submit complete")
            } finally {
                isBusy = false
            }
        }
    }

    fun onClearOutput() {
        outputMode = OutputMode.Empty
        isOutputOpen = false
    }

    fun onToggleOutput() {
        isOutputOpen = !isOutputOpen
    }

    fun onToggleProblemPanel() {
        isProblemPanelOpen = !isProblemPanelOpen
        println("[CodeEditorState] Problem panel open: $isProblemPanelOpen (html=${problemHtml.length}c css=${problemCss.length}c)")
    }

    fun onToggleFocusMode() {
        isFocusMode = !isFocusMode
        if (isFocusMode) {
            isProblemPanelOpen = false
            isCustomInputExpanded = false
            isOutputOpen = false
        } else {
            isProblemPanelOpen = true
            isCustomInputExpanded = true
        }
        println("[CodeEditorState] Focus mode: $isFocusMode")
    }

    // Returns an error OutputMode when ALL results share a terminal status that makes the
    // test table meaningless (CE = never compiled; RTE = always crashed; TLE/MLE/JE = uniform
    // judge verdict). Returns null for mixed results or normal runs — those go to the test table.
    private fun terminalErrorOrNull(response: data.TestResultsResponse): OutputMode.Error? {
        val results = response.results
        if (results.isEmpty()) return null
        return when {
            results.all { it.status == "CE" } -> OutputMode.Error(
                RuntimeError("Compiler Error", sanitizeCodeOutput(results.first().actualOutput))
            )
            results.all { it.status == "RTE" } -> OutputMode.Error(
                RuntimeError(
                    "Runtime Error",
                    results.first().stderr.takeIf { it.isNotBlank() }
                        ?.let { sanitizeCodeOutput(it) }
                        ?: "Your solution crashed at runtime.\nCheck for null pointer exceptions, array index out of bounds, or stack overflow."
                )
            )
            results.all { it.status == "TLE" } -> OutputMode.Error(
                RuntimeError(
                    "Time Limit Exceeded",
                    results.first().actualOutput.takeIf { it.isNotBlank() }
                        ?: "Your solution exceeded the time limit.\nCheck for infinite loops or algorithms with high time complexity."
                )
            )
            results.all { it.status == "MLE" } -> OutputMode.Error(
                RuntimeError(
                    "Memory Limit Exceeded",
                    results.first().actualOutput.takeIf { it.isNotBlank() }
                        ?: "Your solution exceeded the memory limit.\nCheck for large data structures or unbounded recursion."
                )
            )
            else -> null
        }
    }

    fun onIncreaseFontSize() {
        if (editorFontSize < EDITOR_MAX_FONT_SIZE) {
            editorFontSize = (editorFontSize.value + 1).sp
            println("[CodeEditorState] 🔤 Font size increased to ${editorFontSize.value.toInt()}sp")
        }
    }

    fun onDecreaseFontSize() {
        if (editorFontSize > EDITOR_MIN_FONT_SIZE) {
            editorFontSize = (editorFontSize.value - 1).sp
            println("[CodeEditorState] 🔤 Font size decreased to ${editorFontSize.value.toInt()}sp")
        }
    }

    companion object {
        private val EDITOR_DEFAULT_FONT_SIZE = 14.sp
        private val EDITOR_MAX_FONT_SIZE     = 24.sp
        private val EDITOR_MIN_FONT_SIZE     = 10.sp
    }
}
