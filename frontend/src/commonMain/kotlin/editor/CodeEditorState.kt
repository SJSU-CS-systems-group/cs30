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
    private val _selectedLanguage = mutableStateOf(DEFAULT_LANGUAGE)
    private val _customInput = mutableStateOf("")
    private val _testCases = mutableStateOf<List<String>>(emptyList())
    private val _outputMode = mutableStateOf<OutputMode>(OutputMode.Empty)
    private val _isOutputOpen = mutableStateOf(false)
    private val _isProblemPanelOpen = mutableStateOf(true)
    private val _editorFontSize = mutableStateOf(14.sp)

    var problemHtml by _problemHtml
    var problemCss by _problemCss
    var isLoading by _isLoading
    var selectedLanguage by _selectedLanguage
    var customInput by _customInput
    var testCases by _testCases
    var outputMode by _outputMode
    var isOutputOpen by _isOutputOpen
    var isProblemPanelOpen by _isProblemPanelOpen
    var editorFontSize by _editorFontSize

    val buffers = mutableMapOf<String, String>()

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

    fun onLanguageChange(lang: String) {
        if (lang != selectedLanguage) {
            println("[CodeEditorState] 🔄 Language change: $selectedLanguage → $lang")
            buffers[selectedLanguage] = codeState.text.toString()
            val target = buffers[lang] ?: STARTER_CODE[lang].orEmpty()
            codeState.edit {
                replace(0, length, target)
            }
            selectedLanguage = lang
        }
    }

    fun onTest() {
        scope.launch {
            println("[CodeEditorState] 🧪 Testing code (${selectedLanguage})")
            val result = backend.testCode(
                TestRequest(
                    courseId = problem.courseId,
                    section = problem.section,
                    labNumber = problem.labNumber,
                    problemName = problem.slug,
                    studentEmail = studentEmail,
                    language = selectedLanguage,
                    code = codeState.text.toString(),
                    stdin = customInput,
                )
            )
            outputMode = OutputMode.Test(result, isSubmit = false)
            isOutputOpen = true
            println("[CodeEditorState] ✅ Test complete")
        }
    }

    fun onSubmit() {
        scope.launch {
            println("[CodeEditorState] ✔️ Submitting code (${selectedLanguage})")
            val result = backend.submitCode(
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
            outputMode = OutputMode.Test(result.response, isSubmit = true)
            isOutputOpen = true
            println("[CodeEditorState] ✅ Submit complete")
        }
    }

    fun onClearOutput() {
        outputMode = OutputMode.Empty
        isOutputOpen = false
    }

    fun onToggleOutput() {
        isOutputOpen = !isOutputOpen
    }

    fun onIncreaseFontSize() {
        if (editorFontSize < 24.sp) {
            editorFontSize = (editorFontSize.value + 1).sp
            println("[CodeEditorState] 🔤 Font size increased to ${editorFontSize.value.toInt()}sp")
        }
    }

    fun onDecreaseFontSize() {
        if (editorFontSize > 10.sp) {
            editorFontSize = (editorFontSize.value - 1).sp
            println("[CodeEditorState] 🔤 Font size decreased to ${editorFontSize.value.toInt()}sp")
        }
    }
}
