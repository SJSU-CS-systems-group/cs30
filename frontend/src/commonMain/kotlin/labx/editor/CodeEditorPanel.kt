@file:OptIn(ExperimentalFoundationApi::class)

package labx.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labx.data.ViolationKind
import labx.lockdown.LocalLockdown
import labx.theme.AccentBlue

private val languages = listOf("Kotlin", "Java", "Python", "C++")

@Composable
fun CodeEditorPanel(
    codeState: TextFieldState,
    onRun: () -> Unit,
    onTest: () -> Unit,
    onSubmit: () -> Unit,
    onClearOutput: () -> Unit,
    onToggleOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLanguage by remember { mutableStateOf("Kotlin") }
    var languageMenuOpen by remember { mutableStateOf(false) }
    val lockdown = LocalLockdown.current
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier) {
        // Language selector + action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { languageMenuOpen = true }) {
                    Text(selectedLanguage, style = MaterialTheme.typography.bodyMedium)
                }
                DropdownMenu(
                    expanded = languageMenuOpen,
                    onDismissRequest = { languageMenuOpen = false }
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang) },
                            onClick = {
                                selectedLanguage = lang
                                languageMenuOpen = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onRun,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) { Text("Run") }

            Spacer(Modifier.width(4.dp))

            OutlinedButton(onClick = onTest) { Text("Test") }

            Spacer(Modifier.width(4.dp))

            OutlinedButton(onClick = onSubmit) { Text("Submit") }

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onClearOutput) { Text("Clear Output") }

            Spacer(Modifier.width(4.dp))

            TextButton(onClick = onToggleOutput) { Text("Toggle Output") }
        }

        // Lined code editor (adapted from sbkmp LinedTextEditor)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scrollState = rememberScrollState()
            val lineCount by remember {
                derivedStateOf { codeState.text.count { it == '\n' } + 1 }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFF0F0F0))
                        .verticalScroll(scrollState, enabled = false)
                        .padding(top = 4.dp, bottom = 4.dp, end = 8.dp, start = 4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    repeat(lineCount) { i ->
                        Text(
                            text = "${i + 1}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = Color(0xFF888888),
                                textAlign = TextAlign.End
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFCCCCCC))
                )

                BasicTextField(
                    state = codeState,
                    scrollState = scrollState,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFF1C1C1C)
                    ),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .onPreviewKeyEvent { e ->
                            if (!lockdown.active.value) return@onPreviewKeyEvent false
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val mod = e.isCtrlPressed || e.isMetaPressed
                            if (!mod) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.C, Key.X -> {
                                    val sel = codeState.selection
                                    if (!sel.collapsed) {
                                        val selected = codeState.text
                                            .substring(sel.min, sel.max)
                                        lockdown.recordOwnCopy(selected)
                                    }
                                    false
                                }
                                Key.V -> {
                                    val pasted = clipboardManager.getText()?.text
                                    if (!lockdown.isOwnClipboardText(pasted)) {
                                        lockdown.report(ViolationKind.PasteFromOutside)
                                        true
                                    } else false
                                }
                                else -> false
                            }
                        }
                )
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
            )
        }
    }
}
