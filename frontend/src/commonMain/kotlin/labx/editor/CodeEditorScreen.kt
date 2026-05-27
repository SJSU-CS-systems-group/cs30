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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import labx.data.MockDataRepository
import labx.data.Student

private const val STARTER_CODE =
    "fun main() {\n    val line = readLine()!!\n    // Write your solution here\n}\n"

@Composable
fun CodeEditorScreen(student: Student, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var problemHtml by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    val codeState = rememberTextFieldState(STARTER_CODE)
    var customInput by remember { mutableStateOf("") }
    var outputMode by remember { mutableStateOf<OutputMode>(OutputMode.Empty) }
    var isOutputOpen by remember { mutableStateOf(false) }
    var isProblemPanelOpen by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        problemHtml = MockDataRepository.getProblemHtml()
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        TopBar(
            student = student,
            isProblemPanelOpen = isProblemPanelOpen,
            onTogglePanel = { isProblemPanelOpen = !isProblemPanelOpen },
            onLogout = onLogout
        )

        if (isLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // Main content row
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Collapsible problem panel
                if (isProblemPanelOpen) {
                    ProblemPanel(html = problemHtml)
                }

                // Divider
                if (isProblemPanelOpen) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }

                // Code editor + custom input
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CodeEditorPanel(
                        codeState = codeState,
                        onRun = {
                            scope.launch {
                                val result = MockDataRepository.getRunOutput()
                                outputMode = OutputMode.Run(result)
                                isOutputOpen = true
                            }
                        },
                        onTest = {
                            scope.launch {
                                val result = MockDataRepository.getTestResults()
                                outputMode = OutputMode.Test(result, isSubmit = false)
                                isOutputOpen = true
                            }
                        },
                        onSubmit = {
                            scope.launch {
                                val result = MockDataRepository.getTestResults()
                                outputMode = OutputMode.Test(result, isSubmit = true)
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

        // Output panel
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
