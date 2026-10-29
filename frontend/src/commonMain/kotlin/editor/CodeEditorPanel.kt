@file:OptIn(ExperimentalFoundationApi::class)

package editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import lockdown.LocalLockdown
import theme.LocalEditorPalette
import theme.MonoTextStyle
import kotlin.math.roundToInt
import kotlin.time.TimeSource

@Composable
fun CodeEditorPanel(
    codeState: TextFieldState,
    history: EditorHistory,
    isCodeLoaded: Boolean,
    selectedLanguage: String,
    onTest: () -> Unit,
    onSubmit: () -> Unit,
    isOutputOpen: Boolean,
    onToggleOutput: () -> Unit,
    isBusy: Boolean = false,
    lastSubmitStatus: String? = null,
    hasEditsAfterSubmit: Boolean = false,
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
            // Language is fixed per problem/course; shown read-only as a themed chip (no switching).
            Box(
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    selectedLanguage,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.width(8.dp))

            OutlinedButton(onClick = onTest, enabled = !isBusy) { Text("Run") }

            Spacer(Modifier.width(4.dp))

            OutlinedButton(onClick = onSubmit, enabled = !isBusy) { Text("Submit") }

            Spacer(Modifier.width(8.dp))
            SubmitStatusChip(lastSubmitStatus, hasEditsAfterSubmit)

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onToggleOutput) {
                Text("Output", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = if (isOutputOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (isOutputOpen) "Close output" else "Open output",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Lined code editor with live syntax highlighting. Compose's BasicTextField(state=) can't
        // color spans, so we render a colored, read-only Text overlay BEHIND a real field whose
        // glyphs are transparent (its caret + selection stay visible). Both layers share the same
        // MonoTextStyle, padding, width, and scrollState so they stay pixel-aligned.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            val scrollState = rememberScrollState()
            val palette = LocalEditorPalette.current
            val density = LocalDensity.current

            // Every change, however it was made, goes into the one undo history (see EditorHistory).
            val clock = remember { TimeSource.Monotonic.markNow() }
            val nowMs = { clock.elapsedNow().inWholeMilliseconds }
            LaunchedEffect(codeState, history) {
                snapshotFlow { codeState.text.toString() to codeState.selection }
                    .collect { (text, selection) -> history.record(text, selection, nowMs()) }
            }
            val codeLoaded by rememberUpdatedState(isCodeLoaded)

            val lineCount by remember {
                derivedStateOf { codeState.text.count { it == '\n' } + 1 }
            }
            val gutterBg = MaterialTheme.colorScheme.surfaceVariant
            val gutterDivider = MaterialTheme.colorScheme.outline
            val cursorColor = palette.focus

            val language = remember(selectedLanguage) { CodeHighlighter.languageFor(selectedLanguage) }
            val codeText by remember { derivedStateOf { codeState.text.toString() } }
            val settings = LocalEditorSettings.current
            // One per editor: it remembers which closers it auto-inserted (see AutoPairTransformation)
            val assistTransformation = remember { FullEditorTransformation() }
            val highlighted = remember(codeText, language, palette.syntax, settings.syntaxHighlighting) {
                if (settings.syntaxHighlighting) CodeHighlighter.highlight(codeText, language, palette.syntax)
                else CodeHighlighter.plain(codeText, palette.syntax)
            }
            val caretOffset by remember {
                derivedStateOf { codeState.selection.start.coerceIn(0, codeState.text.length) }
            }
            var codeLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val editorPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            val topPadPx = with(density) { 4.dp.roundToPx() }

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
                            style = MonoTextStyle.copy(color = palette.lineNumber, textAlign = TextAlign.End),
                            modifier = Modifier.height(MonoTextStyle.lineHeight.value.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(gutterDivider)
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    // Current-line highlight band (bottom layer), placed from the overlay's text
                    // layout (wrap-accurate) and shifted by the shared scroll offset.
                    codeLayout?.let { layout ->
                        if (codeText.isNotEmpty() || caretOffset == 0) {
                            val visualLine = layout.getLineForOffset(caretOffset)
                            val top = layout.getLineTop(visualLine)
                            val bottom = layout.getLineBottom(visualLine)
                            val yPx = topPadPx + top.roundToInt() - scrollState.value
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset { IntOffset(0, yPx) }
                                    .height(with(density) { (bottom - top).toDp() })
                                    .background(palette.currentLine)
                            )
                        }
                    }

                    // Indent guides: vertical lines at each indentation level
                    val indentGuideColor = palette.indentGuide
                    val indentSize = 4 // spaces per indent level
                    val leftPadPx = with(density) { 8.dp.toPx() } // matches editorPadding horizontal
                    codeLayout?.let { layout ->
                        // Estimate character width from layout (monospace, so any char works)
                        // Need at least 2 chars in the layout to measure width between positions
                        val layoutTextLength = layout.layoutInput.text.length
                        val charWidth = if (layoutTextLength >= 2) {
                            val firstCharEnd = layout.getHorizontalPosition(1, true)
                            val firstCharStart = layout.getHorizontalPosition(0, true)
                            (firstCharEnd - firstCharStart).coerceAtLeast(1f)
                        } else {
                            with(density) { 7.8.dp.toPx() } // fallback estimate for 13sp mono
                        }

                        val lines = codeText.split('\n')
                        val maxIndentLevels = lines.map { line ->
                            val leadingSpaces = line.takeWhile { it == ' ' }.length
                            leadingSpaces / indentSize
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset { IntOffset(0, topPadPx - scrollState.value) }
                        ) {
                            val lineHeightPx = layout.multiParagraph.height / layout.lineCount.coerceAtLeast(1)

                            lines.forEachIndexed { lineIndex, _ ->
                                val indentLevels = maxIndentLevels[lineIndex]

                                // Only draw guides for lines with actual indentation (VSCode behavior)
                                if (indentLevels > 0) {
                                    val yTop = lineIndex * lineHeightPx
                                    val yBottom = yTop + lineHeightPx

                                    for (level in 1..indentLevels) {
                                        val x = leftPadPx + (level * indentSize * charWidth) - (charWidth / 2)
                                        drawLine(
                                            color = indentGuideColor,
                                            start = Offset(x, yTop),
                                            end = Offset(x, yBottom),
                                            strokeWidth = 1f
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Colored overlay: the visible code. Non-interactive; scrolls with the field.
                    Text(
                        text = highlighted,
                        style = MonoTextStyle,
                        softWrap = true,
                        onTextLayout = { codeLayout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState, enabled = false)
                            .padding(editorPadding)
                    )

                    // Real editable field on top: transparent glyphs, visible caret + selection.
                    BasicTextField(
                        state = codeState,
                        scrollState = scrollState,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        textStyle = MonoTextStyle.copy(color = Color.Transparent),
                        cursorBrush = SolidColor(cursorColor),
                        // Read-only until the saved code has loaded, so it can't land on top of new typing
                        readOnly = !isCodeLoaded,
                        // Enter/Tab indentation, plus bracket/quote auto-pairing when the student has it on
                        inputTransformation = if (settings.bracketAssist) assistTransformation else CodeInputTransformation,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(editorPadding)
                            // Bridge web paste: Compose's BasicTextField(state=) doesn't insert pasted
                            // text on wasm (issue #4036). While focused, register how to insert an
                            // allowed (own) paste; the web DOM paste handler calls this. Cleared on blur.
                            .onFocusChanged { focus ->
                                lockdown.setPasteSink(
                                    if (focus.isFocused) { pasted ->
                                        if (codeLoaded) {
                                            codeState.edit {
                                                val sel = selection
                                                replace(sel.min, sel.max, pasted)
                                                selection = TextRange(sel.min + pasted.length)
                                            }
                                        }
                                    } else null
                                )
                            }
                            // Lockdown clipboard policy: own copy/cut recorded, outside paste blocked.
                            .lockdownClipboardGuard {
                                val sel = codeState.selection
                                if (sel.collapsed) null else codeState.text.substring(sel.min, sel.max)
                            }
                            // Shift+Tab for unindent, Backspace for bracket pair deletion.
                            // Regular Tab is handled by InputTransformation (converts \t to spaces).
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // Nothing edits the code until the saved code has loaded
                                if (!codeLoaded) return@onPreviewKeyEvent true
                                val shortcut = e.isCtrlPressed || e.isMetaPressed

                                // Undo/redo go through the editor's own history, never Compose's
                                if (shortcut) {
                                    if (e.key == Key.Z || e.key == Key.Y) {
                                        val isRedo = e.key == Key.Y || e.isShiftPressed
                                        val text = codeState.text.toString()
                                        val restored = if (isRedo) {
                                            history.redo(text, codeState.selection, nowMs())
                                        } else {
                                            history.undo(text, codeState.selection, nowMs())
                                        }
                                        restored?.let {
                                            codeState.edit {
                                                replace(0, length, it.text)
                                                selection = it.selection
                                            }
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    // Let other shortcuts (copy, paste, etc.) pass through
                                    return@onPreviewKeyEvent false
                                }

                                when (e.key) {
                                    // Shift+Tab unindents, Tab over a selection indents it; a plain Tab
                                    // goes through CodeInputTransformation (inserts spaces)
                                    Key.Tab -> when {
                                        e.isShiftPressed -> { codeState.handleShiftTab(); true }
                                        !codeState.selection.collapsed -> { codeState.handleTabIndent(); true }
                                        else -> false
                                    }
                                    Key.Backspace -> settings.bracketAssist && codeState.handleBackspacePair()
                                    else -> false
                                }
                            }
                    )

                    if (!isCodeLoaded) {
                        Text(
                            "Loading your saved code…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                        )
                    }
                }
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

@Composable
private fun SubmitStatusChip(status: String?, hasEditsAfterSubmit: Boolean) {
    val palette = LocalEditorPalette.current
    when {
        status == null -> {
            Text(
                text = "No submissions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        !hasEditsAfterSubmit -> {
            // Colour AND name the verdict — a red chip reading only "Submitted" would signal
            // the failure by colour alone, which the rest of the app deliberately avoids.
            val color = verdictColor(status)
            Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
                Text(
                    text = "Submitted · ${verdictShortLabel(status)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        else -> {
            Surface(shape = RoundedCornerShape(4.dp), color = palette.warning.copy(alpha = 0.15f)) {
                Text(
                    text = "Changes not submitted",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.warning,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
