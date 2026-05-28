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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import labx.backend.BackendService
import labx.backend.RunRequest
import labx.backend.SubmitRequest
import labx.backend.TestRequest
import labx.data.MockDataRepository
import labx.data.Student

@Composable
fun CodeEditorScreen(
    student: Student,
    backend: BackendService,
    onSubmitExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var problemHtml by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    val codeState = rememberTextFieldState(STARTER_CODE.getValue(DEFAULT_LANGUAGE))
    var selectedLanguage by remember { mutableStateOf(DEFAULT_LANGUAGE) }
    var customInput by remember { mutableStateOf("") }
    var outputMode by remember { mutableStateOf<OutputMode>(OutputMode.Empty) }
    var isOutputOpen by remember { mutableStateOf(false) }
    var isProblemPanelOpen by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        problemHtml = MockDataRepository.getProblemHtml()
        isLoading = false
    }

    val onLanguageChange: (String) -> Unit = { lang ->
        if (lang != selectedLanguage) {
            val previousStarter = STARTER_CODE[selectedLanguage].orEmpty()
            val untouched = codeState.text.toString() == previousStarter
            selectedLanguage = lang
            if (untouched) {
                val next = STARTER_CODE[lang].orEmpty()
                codeState.edit {
                    replace(0, length, next)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            student = student,
            isProblemPanelOpen = isProblemPanelOpen,
            onTogglePanel = { isProblemPanelOpen = !isProblemPanelOpen },
            onSubmitExit = onSubmitExit
        )

        if (isLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isProblemPanelOpen) {
                    ProblemPanel(html = problemHtml, interactive = false)
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CodeEditorPanel(
                        codeState = codeState,
                        selectedLanguage = selectedLanguage,
                        onLanguageChange = onLanguageChange,
                        onRun = {
                            scope.launch {
                                val result = backend.runCode(
                                    RunRequest(
                                        language = selectedLanguage,
                                        code = codeState.text.toString(),
                                        stdin = customInput,
                                    )
                                )
                                outputMode = OutputMode.Run(result)
                                isOutputOpen = true
                            }
                        },
                        onTest = {
                            scope.launch {
                                val result = backend.testCode(
                                    TestRequest(
                                        language = selectedLanguage,
                                        code = codeState.text.toString(),
                                        stdin = customInput,
                                    )
                                )
                                outputMode = OutputMode.Test(result, isSubmit = false)
                                isOutputOpen = true
                            }
                        },
                        onSubmit = {
                            scope.launch {
                                val result = backend.submitCode(
                                    SubmitRequest(
                                        language = selectedLanguage,
                                        code = codeState.text.toString(),
                                    )
                                )
                                outputMode = OutputMode.Test(result.response, isSubmit = true)
                                isOutputOpen = true
                            }
                        },
                        onClearOutput = {
                            outputMode = OutputMode.Empty
                            isOutputOpen = false
                        },
                        onToggleOutput = { isOutputOpen = !isOutputOpen },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                    CustomInputPanel(
                        value = customInput,
                        onValueChange = { customInput = it }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isOutputOpen,
            enter = expandVertically(expandFrom = Alignment.Bottom),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            OutputPanel(
                outputMode = outputMode,
                onClose = { isOutputOpen = false }
            )
        }
    }
}
