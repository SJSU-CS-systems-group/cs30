@file:OptIn(ExperimentalFoundationApi::class)

package editor

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lockdown.LocalLockdown
import theme.MonoTextStyle

@Composable
fun CodeEditorPanel(
    codeState: TextFieldState,
    selectedLanguage: String,
    onTest: () -> Unit,
    onSubmit: () -> Unit,
    onClearOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lockdown = LocalLockdown.current
    Column(modifier = modifier) {
        // Language selector + action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Language is fixed per problem/course; shown read-only (no switching).
            Text(
                selectedLanguage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.width(8.dp))

            OutlinedButton(onClick = onTest) { Text("Run") }

            Spacer(Modifier.width(4.dp))

            OutlinedButton(onClick = onSubmit) { Text("Submit") }

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onClearOutput) { Text("Clear Output") }
        }

        // Lined code editor (adapted from sbkmp LinedTextEditor)
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            val scrollState = rememberScrollState()
            val lineCount by remember {
                derivedStateOf { codeState.text.count { it == '\n' } + 1 }
            }
            val gutterBg = MaterialTheme.colorScheme.surfaceVariant
            val gutterText = MaterialTheme.colorScheme.onSurfaceVariant
            val gutterDivider = MaterialTheme.colorScheme.outline
            val codeText = MaterialTheme.colorScheme.onSurface
            val cursorColor = MaterialTheme.colorScheme.primary

            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .background(gutterBg)
                        .verticalScroll(scrollState, enabled = false)
                        .padding(top = 4.dp, bottom = 4.dp, end = 8.dp, start = 4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    repeat(lineCount) { i ->
                        Text(
                            text = "${i + 1}",
                            style = MonoTextStyle.copy(color = gutterText, textAlign = TextAlign.End)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(gutterDivider)
                )

                BasicTextField(
                    state = codeState,
                    scrollState = scrollState,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    textStyle = MonoTextStyle.copy(color = codeText),
                    cursorBrush = SolidColor(cursorColor),
                    inputTransformation = AutoPairTransformation,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        // Bridge web paste: Compose's BasicTextField(state=) doesn't insert pasted
                        // text on wasm (issue #4036). While focused, register how to insert an
                        // allowed (own) paste; the web DOM paste handler calls this. Cleared on blur.
                        .onFocusChanged { focus ->
                            lockdown.setPasteSink(
                                if (focus.isFocused) { pasted ->
                                    codeState.edit {
                                        val sel = selection
                                        replace(sel.min, sel.max, pasted)
                                        selection = TextRange(sel.min + pasted.length)
                                    }
                                } else null
                            )
                        }
                        // Lockdown clipboard policy: own copy/cut recorded, outside paste blocked.
                        .lockdownClipboardGuard {
                            val sel = codeState.selection
                            if (sel.collapsed) null else codeState.text.substring(sel.min, sel.max)
                        }
                        // IDE-like indentation (4-space soft tabs) + bracket pairing via Backspace.
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (e.isCtrlPressed || e.isMetaPressed) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Tab -> {
                                    if (e.isShiftPressed) codeState.handleShiftTab() else codeState.handleTab()
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    codeState.handleEnter()
                                    true
                                }
                                Key.Backspace -> codeState.handleBackspacePair()
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
