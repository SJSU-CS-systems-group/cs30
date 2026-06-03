package labx.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import labx.backend.BackendService
import labx.backend.RunRequest
import labx.backend.SubmitRequest
import labx.backend.TestRequest
import labx.data.MockDataRepository
import labx.data.ProblemSummary

@Stable
class CodeEditorState(
    val problem: ProblemSummary,
    val backend: BackendService,
    val scope: CoroutineScope,
    val codeState: TextFieldState,
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

    var problemHtml by _problemHtml
    var problemCss by _problemCss
    var isLoading by _isLoading
    var selectedLanguage by _selectedLanguage
    var customInput by _customInput
    var testCases by _testCases
    var outputMode by _outputMode
    var isOutputOpen by _isOutputOpen
    var isProblemPanelOpen by _isProblemPanelOpen

    val buffers = mutableMapOf<String, String>()

    init {
        println("[CodeEditorState] 🔨 Init: loading problem ${problem.slug}")
        System.out.flush()
        load()
    }

    private fun load() {
        scope.launch {
            println("[CodeEditorState] 📋 Loading HTML + CSS for ${problem.slug}")
            System.out.flush()
            isLoading = true
            problemHtml = MockDataRepository.getProblemHtml(problem.slug)
            problemCss = MockDataRepository.getProblemCss()
            isLoading = false
            println("[CodeEditorState] ✅ HTML + CSS loaded")
            System.out.flush()
        }
    }

    fun onLanguageChange(lang: String) {
        if (lang != selectedLanguage) {
            println("[CodeEditorState] 🔄 Language change: $selectedLanguage → $lang")
            System.out.flush()
            buffers[selectedLanguage] = codeState.text.toString()
            val target = buffers[lang] ?: STARTER_CODE[lang].orEmpty()
            codeState.edit {
                replace(0, length, target)
            }
            selectedLanguage = lang
        }
    }

    fun onRun() {
        scope.launch {
            println("[CodeEditorState] 🚀 Running code (${selectedLanguage})")
            System.out.flush()
            val result = backend.runCode(
                RunRequest(
                    language = selectedLanguage,
                    code = codeState.text.toString(),
                    stdin = customInput,
                )
            )
            outputMode = OutputMode.Run(result)
            isOutputOpen = true
            println("[CodeEditorState] ✅ Run complete")
            System.out.flush()
        }
    }

    fun onTest() {
        scope.launch {
            println("[CodeEditorState] 🧪 Testing code (${selectedLanguage})")
            System.out.flush()
            val result = backend.testCode(
                TestRequest(
                    language = selectedLanguage,
                    code = codeState.text.toString(),
                    stdin = customInput,
                )
            )
            outputMode = OutputMode.Test(result, isSubmit = false)
            isOutputOpen = true
            println("[CodeEditorState] ✅ Test complete")
            System.out.flush()
        }
    }

    fun onSubmit() {
        scope.launch {
            println("[CodeEditorState] ✔️ Submitting code (${selectedLanguage})")
            System.out.flush()
            val result = backend.submitCode(
                SubmitRequest(
                    language = selectedLanguage,
                    code = codeState.text.toString(),
                )
            )
            outputMode = OutputMode.Test(result.response, isSubmit = true)
            isOutputOpen = true
            println("[CodeEditorState] ✅ Submit complete")
            System.out.flush()
        }
    }

    fun onClearOutput() {
        outputMode = OutputMode.Empty
        isOutputOpen = false
    }

    fun onToggleOutput() {
        isOutputOpen = !isOutputOpen
    }
}
