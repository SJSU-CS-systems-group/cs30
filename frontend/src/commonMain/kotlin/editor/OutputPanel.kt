package editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.RuntimeError
import data.TestResult
import data.TestResultsResponse
import theme.CodeFont
import theme.LocalEditorPalette
import theme.MonoTextStyle

sealed class OutputMode {
    data object Empty : OutputMode()
    // statusText: null shows the default "Running…"; otherwise a one-time "N in process" snapshot set
    // at submission, or a generic refresh message set later — never both at once. See CodeEditorState.
    data class Loading(val statusText: String? = null) : OutputMode()
    data class Test(val response: TestResultsResponse, val isSubmit: Boolean) : OutputMode()
    // isRetryable: true for network/infra failures where the student can meaningfully try again.
    // false for code verdicts (CE, RTE, TLE, MLE) where a retry without code changes is pointless.
    data class Error(val error: RuntimeError, val isRetryable: Boolean = false) : OutputMode()
}

private val DRAG_HANDLE_HIT_HEIGHT    = 8.dp
private val DRAG_HANDLE_VISUAL_HEIGHT = 1.dp

@Composable
fun OutputPanel(
    outputMode: OutputMode,
    onClose: () -> Unit,
    onDrag: (delta: Dp) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val headerTitle = when (outputMode) {
        is OutputMode.Empty, is OutputMode.Loading -> "Output"
        is OutputMode.Test  -> if (outputMode.isSubmit) "Submit Results" else "Run Results"
        is OutputMode.Error -> outputMode.error.status
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            OutputDragHandle(onDrag = onDrag)

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                CloseButton(onClick = onClose)
            }

            HorizontalDivider()

            when (outputMode) {
                is OutputMode.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(outputMode.statusText ?: "Running…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onRefresh) { Text("Refresh") }
                        }
                    }
                }

                is OutputMode.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.padding(bottom = 8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Click Run or Submit to see results",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is OutputMode.Test -> TestResultsView(
                    response = outputMode.response,
                    isSubmit = outputMode.isSubmit
                )

                is OutputMode.Error -> ErrorView(outputMode.error, outputMode.isRetryable, onRetry)
            }
        }
    }
}

@Composable
private fun OutputDragHandle(onDrag: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DRAG_HANDLE_HIT_HEIGHT)
            .resizeCursorModifier(ResizeAxis.VERTICAL)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    onDrag(with(density) { dragAmount.toDp() })
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DRAG_HANDLE_VISUAL_HEIGHT)
                .align(Alignment.Center)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

@Composable
private fun TestResultsView(response: TestResultsResponse, isSubmit: Boolean) {
    if (response.results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = response.status.ifBlank { "No results." },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    val gradedResults = response.results.filter { it.status != null }
    val passCount = gradedResults.count { it.passed }
    val totalGraded = gradedResults.size
    val palette = LocalEditorPalette.current
    val summaryColor = if (passCount == totalGraded) palette.pass else palette.fail

    Column(modifier = Modifier.fillMaxWidth()) {
        if (gradedResults.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$passCount / $totalGraded Passed",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = summaryColor
                )
                if (response.status.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· ${response.status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
        } else if (response.status.isNotBlank()) {
            Text(
                text = response.status,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider()
        }

        // Column header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("#",        modifier = Modifier.width(28.dp),  style = headerStyle)
            Text("Input",    modifier = Modifier.weight(2f),    style = headerStyle)
            Text("Expected", modifier = Modifier.weight(1.5f),  style = headerStyle)
            Text("Actual",   modifier = Modifier.weight(1.5f),  style = headerStyle)
            Text("Result",   modifier = Modifier.width(160.dp), style = headerStyle)
        }
        HorizontalDivider()

        LazyColumn {
            items(response.results) { result ->
                TestResultRow(result)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun TestResultRow(result: TestResult) {
    val ungraded = result.status == null
    val palette = LocalEditorPalette.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val rowBg = when {
        ungraded -> neutral.copy(alpha = 0.10f)
        result.passed -> palette.pass.copy(alpha = 0.12f)
        else -> palette.fail.copy(alpha = 0.12f)
    }
    val badgeColor = when {
        ungraded -> neutral
        result.passed -> palette.pass
        else -> palette.fail
    }
    val mono = TextStyle(fontFamily = CodeFont, fontSize = 12.sp, color = textColor)

    val displayExpected = when {
        result.hidden -> "—"
        result.expectedOutput.isEmpty() -> "(empty)"
        else -> result.expectedOutput
    }
    val displayActual = when {
        result.hidden -> "—"
        result.actualOutput.isEmpty() -> "(empty)"
        else -> result.actualOutput
    }

    // Only enable expand/collapse when at least one content field has multiple lines.
    val isMultiline = !result.hidden && (
        hasMultipleVisibleLines(result.input) ||
        hasMultipleVisibleLines(result.expectedOutput) ||
        hasMultipleVisibleLines(result.actualOutput)
    )

    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
    val maxLines = if (isMultiline && !expanded) 2 else Int.MAX_VALUE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .then(if (isMultiline) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "${result.testCase}", modifier = Modifier.width(28.dp), style = mono)
        if (result.hidden) {
            Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "Hidden test case",
                    modifier = Modifier.size(14.dp),
                    tint = neutral
                )
                Spacer(Modifier.width(4.dp))
                Text(text = "Hidden", style = mono.copy(color = neutral))
            }
        } else {
            Text(text = sanitizeCodeOutput(result.input), modifier = Modifier.weight(2f), style = mono, maxLines = maxLines)
        }
        val sanitizedExpected = sanitizeCodeOutput(displayExpected)
        val sanitizedActual = sanitizeCodeOutput(displayActual)
        // A failed row diffs the two sides against each other character by character: over the output
        // that marks what is wrong or extra, over the expected output what is missing. Needs both
        // sides to have content — placeholders like "(empty)" are not text the student wrote.
        val diffable = !ungraded && !result.passed && !result.hidden &&
            result.actualOutput.isNotEmpty() && result.expectedOutput.isNotEmpty()
        val expectedText = remember(sanitizedExpected, sanitizedActual, palette, textColor, diffable) {
            if (diffable) annotateDiff(sanitizedExpected, sanitizedActual, palette.pass, textColor, palette.fail)
            else AnnotatedString(sanitizedExpected)
        }
        val actualText = remember(sanitizedActual, sanitizedExpected, palette, textColor, diffable) {
            if (diffable) annotateDiff(sanitizedActual, sanitizedExpected, palette.pass, textColor, palette.fail)
            else AnnotatedString(sanitizedActual)
        }
        // Nothing colored on either side means the difference is blank lines only; say so in words.
        val hint = remember(sanitizedActual, sanitizedExpected, diffable) {
            if (diffable) invisibleDiffHint(sanitizedActual, sanitizedExpected) else null
        }

        Text(text = expectedText, modifier = Modifier.weight(1.5f), style = mono, maxLines = maxLines)
        Row(modifier = Modifier.weight(1.5f), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = actualText,
                    style = mono,
                    color = if (!diffable && !ungraded && !result.passed) palette.fail else textColor,
                    maxLines = maxLines
                )
                if (hint != null) {
                    Text(text = hint, style = MaterialTheme.typography.labelSmall, color = palette.fail)
                }
            }
            if (isMultiline) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse row" else "Expand row",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                        .rotate(chevronRotation),
                    tint = neutral
                )
            }
        }
        Column(modifier = Modifier.width(160.dp)) {
            StatusBadge(label = statusLabel(result.status), color = badgeColor)
            result.executionTimeMs?.let { ms ->
                Text(
                    text = "${ms}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral
                )
            }
        }
    }
}

/**
 * Colors [text] against [against]. Only a line that is identical to its counterpart gets the pass
 * color: characters that merely appear on the other side stay neutral, so an output that is just a
 * subsequence of the expected one (every character right, some missing) never reads as correct.
 */
private fun annotateDiff(
    text: String,
    against: String,
    exactColor: Color,
    neutralColor: Color,
    diffColor: Color,
): AnnotatedString {
    val lines = text.split("\n")
    val diffed = diffLines(lines, against.split("\n"))
    val exactStyle = SpanStyle(color = exactColor)
    val neutralStyle = SpanStyle(color = neutralColor)
    // Differing runs also get a tint, so a difference in spaces is visible and not just red on nothing.
    val diffStyle = SpanStyle(color = diffColor, background = diffColor.copy(alpha = 0.18f))
    return buildAnnotatedString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append("\n")
            val diffLine = diffed[i]
            diffLine.spans.forEach { span ->
                val style = when {
                    span.mark == DiffMark.DIFF -> diffStyle
                    diffLine.exact -> exactStyle
                    else -> neutralStyle
                }
                withStyle(style) { append(line.substring(span.start, span.end)) }
            }
        }
    }
}

@Composable
private fun ErrorView(error: RuntimeError, isRetryable: Boolean, onRetry: () -> Unit) {
    val palette = LocalEditorPalette.current
    val nonBlankLines = error.stderr.lines().filter { it.isNotBlank() }
    // Single-line errors (e.g. "Segmentation fault") get the prominent headline treatment.
    // Multi-line output (CE, complex RTE) goes into one code block — no artificial headline split.
    val isSingleLine = nonBlankLines.size <= 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        StatusBadge(error.status, palette.fail)
        Spacer(Modifier.height(8.dp))
        if (isSingleLine) {
            Text(
                text = nonBlankLines.firstOrNull() ?: error.status,
                style = TextStyle(
                    fontFamily = CodeFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.fail
                )
            )
        } else {
            CodeBlock("output", error.stderr.trim(), labelColor = palette.fail)
        }
        if (isRetryable) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("Try Again") }
        }
    }
}

@Composable
private fun CodeBlock(
    label: String,
    content: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text(
                text = content,
                style = TextStyle(
                    fontFamily = CodeFont,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = CodeFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
    }
}

private fun statusLabel(status: String?): String = when (status) {
    null -> "Executed"
    "AC" -> "Accepted"
    "WA" -> "Wrong Answer"
    "TLE" -> "Time Limit Exceeded"
    "RTE" -> "Run Time Error"
    "MLE" -> "Memory Limit Exceeded"
    "CE" -> "Compiler Error"
    "JE" -> "Judge Error"
    else -> status
}

// A trailing newline (common: judge stdin files and println()-based stdout both end with one)
// isn't a second visible line — only an *embedded* newline means the content is really multi-line.
private fun hasMultipleVisibleLines(text: String): Boolean =
    text.trimEnd('\n').contains('\n')

// Sanitizes code execution output for safe display with a specific font (no OS glyph fallback on wasm).
// Strips: ANSI escape sequences (ESC + bracket sequence), non-printable control characters,
//         absolute server/judge paths (anywhere in a line, not just at start).
// Normalises: tabs → 4 spaces.
internal fun sanitizeCodeOutput(text: String): String =
    text
        .replace(Regex("\\[[0-9;]*[a-zA-Z]"), "")
        .replace("\t", "    ")
        .replace(Regex("[ --]"), "")
        .lines()
        .joinToString("\n") { line ->
            line.replace(Regex("/(?:[^/\\s]+/)+([^/:\\s]+(?::\\d+)*:?)"), "$1")
        }

private val headerStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
)
