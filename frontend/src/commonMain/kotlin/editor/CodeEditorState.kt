package editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
    private val _loadError = mutableStateOf(false)
    private val _autosaveError = mutableStateOf(false)
    // Cycles GENERIC_STATUS_MESSAGES on each Refresh click — local UI state only, no network call.
    private var genericStatusIndex = 0
    // Stores the last run/submit action so the retry button can re-issue it.
    private var lastAction: (() -> Unit)? = null

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
    var loadError by _loadError
    var autosaveError by _autosaveError

    init {
        println("[CodeEditorState] Init: loading problem ${problem.slug}")
        load()
    }

    private fun load() {
        scope.launch {
            println("[CodeEditorState] Loading content for ${problem.slug}")
            isLoading = true
            try {
                val content = repository.getProblemContent(
                    problem.courseId,
                    problem.section,
                    problem.labNumber,
                    problem.slug
                )
                problemHtml = content.html
                problemCss = content.css
                println("[CodeEditorState] Content loaded (html: ${content.html.length} bytes, css: ${content.css.length} bytes)")
            } catch (e: Exception) {
                println("[CodeEditorState] Failed to load problem content: ${e.message}")
                loadError = true
            } finally {
                isLoading = false
            }
        }
    }

    fun onTest() {
        if (isBusy) return
        lastAction = this::onTest
        scope.launch {
            isBusy = true
            genericStatusIndex = 0
            try {
                println("[CodeEditorState] 🧪 Testing code (${selectedLanguage})")
                isOutputOpen = true
                outputMode = OutputMode.Loading()
                // Run the queued cases; if none queued, fall back to the input box as a single quick case.
                val customs = testCases.ifEmpty { if (customInput.isNotBlank()) listOf(customInput) else emptyList() }
                // Launched now, awaited below — the request is underway before we check queue status,
                // so the count reflects a submission actually in flight, not a pre-check.
                val resultDeferred = scope.async {
                    backend.testCode(
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
                }
                outputMode = OutputMode.Loading(statusText = fetchInitialQueueStatusText())
                outputMode = try {
                    val response = resultDeferred.await()
                    terminalErrorOrNull(response) ?: OutputMode.Test(response, isSubmit = false)
                } catch (e: Exception) {
                    println("[CodeEditorState] onTest failed: ${e.message}")
                    OutputMode.Error(
                        RuntimeError("Connection Error",
                            "Unable to reach the server.\nPlease check your connection and try again."),
                        isRetryable = true
                    )
                }
                println("[CodeEditorState] ✅ Test complete")
            } finally {
                isBusy = false
            }
        }
    }

    fun onSubmit() {
        if (isBusy) return
        lastAction = this::onSubmit
        scope.launch {
            isBusy = true
            genericStatusIndex = 0
            try {
                println("[CodeEditorState] ✔️ Submitting code (${selectedLanguage})")
                isOutputOpen = true
                outputMode = OutputMode.Loading()
                // Launched now, awaited below — the request is underway before we check queue status,
                // so the count reflects a submission actually in flight, not a pre-check.
                val resultDeferred = scope.async {
                    backend.submitCode(
                        SubmitRequest(
                            courseId = problem.courseId,
                            section = problem.section,
                            labNumber = problem.labNumber,
                            problemName = problem.slug,
                            studentEmail = studentEmail,
                            language = selectedLanguage,
                            code = codeState.text.toString(),
                        )
                    )
                }
                outputMode = OutputMode.Loading(statusText = fetchInitialQueueStatusText())
                outputMode = try {
                    val response = resultDeferred.await().response
                    terminalErrorOrNull(response) ?: OutputMode.Test(response, isSubmit = true)
                } catch (e: Exception) {
                    println("[CodeEditorState] onSubmit failed: ${e.message}")
                    OutputMode.Error(
                        RuntimeError("Connection Error",
                            "Unable to reach the server.\nPlease check your connection and try again."),
                        isRetryable = true
                    )
                }
                println("[CodeEditorState] ✅ Submit complete")
            } finally {
                isBusy = false
            }
        }
    }

    // A one-time, honest snapshot of system-wide judge load — fetched once the real request is already
    // underway (see onTest()/onSubmit()). Never re-fetched afterward; refreshQueueStatus() below cycles
    // generic status text instead, since this count can't honestly represent this specific request's
    // progress once it's several fetches stale. Only surfaced when it's actually meaningful (something
    // else is genuinely in flight) — "0 in process" tells the student nothing useful, so an idle judge
    // just falls back to the default "Running…" text instead.
    private suspend fun fetchInitialQueueStatusText(): String? =
        try {
            val inFlight = backend.queueStatus().inFlight
            when {
                inFlight <= 0 -> null
                inFlight == 1 -> "1 submission being judged, please wait"
                else -> "$inFlight submissions being judged, please wait"
            }
        } catch (e: Exception) {
            println("[CodeEditorState] queueStatus fetch failed: ${e.message}")
            null
        }

    // Refresh button while still waiting: no network call, no re-derived count — just a generic,
    // honest reassurance that the request is still alive.
    fun refreshQueueStatus() {
        if (!isBusy) return
        val current = outputMode
        if (current !is OutputMode.Loading) return
        outputMode = current.copy(statusText = GENERIC_STATUS_MESSAGES[genericStatusIndex % GENERIC_STATUS_MESSAGES.size])
        genericStatusIndex++
    }

    fun retryLastAction() {
        lastAction?.invoke()
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

    // Returns an error OutputMode when either (a) the backend explicitly reported the request
    // itself as failed — grading never happened, so an empty/absent results list must never be
    // silently shown as if it were a normal (if uneventful) completed run — or (b) ALL results
    // share a terminal status that makes the test table meaningless (CE = never compiled; RTE =
    // always crashed; TLE/MLE/JE = uniform judge verdict). Returns null for mixed results or
    // normal runs — those go to the test table.
    private fun terminalErrorOrNull(response: data.TestResultsResponse): OutputMode.Error? {
        if (!response.success) {
            return OutputMode.Error(
                RuntimeError("Submission Error",
                    "Something went wrong with your submission.\nPlease try again or contact your TA."),
                isRetryable = true
            )
        }
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
                        ?.let { sanitizeCodeOutput(it) }
                        ?: "Your solution exceeded the time limit.\nCheck for infinite loops or algorithms with high time complexity."
                )
            )
            results.all { it.status == "MLE" } -> OutputMode.Error(
                RuntimeError(
                    "Memory Limit Exceeded",
                    results.first().actualOutput.takeIf { it.isNotBlank() }
                        ?.let { sanitizeCodeOutput(it) }
                        ?: "Your solution exceeded the memory limit.\nCheck for large data structures or unbounded recursion."
                )
            )
            results.all { it.status == "JE" } -> OutputMode.Error(
                RuntimeError("Submission Error",
                    "Something went wrong processing your submission.\nPlease contact your TA or instructor.")
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
        private val GENERIC_STATUS_MESSAGES = listOf(
            "Still processing your submission…",
            "The judge is working on it…",
            "Please wait, grading in progress…",
        )
    }
}
