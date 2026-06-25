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
import androidx.compose.ui.text.TextStyle
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
    data object Loading : OutputMode()
    data class Test(val response: TestResultsResponse, val isSubmit: Boolean) : OutputMode()
    data class Error(val error: RuntimeError) : OutputMode()
}

private val DRAG_HANDLE_HIT_HEIGHT    = 8.dp
private val DRAG_HANDLE_VISUAL_HEIGHT = 1.dp

@Composable
fun OutputPanel(
    outputMode: OutputMode,
    onClose: () -> Unit,
    onDrag: (delta: Dp) -> Unit = {},
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
                            Text("Running…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                is OutputMode.Error -> ErrorView(outputMode.error)
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
        result.input.contains('\n') ||
        result.expectedOutput.contains('\n') ||
        result.actualOutput.contains('\n')
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
            Text(text = result.input, modifier = Modifier.weight(2f), style = mono, maxLines = maxLines)
        }
        Text(text = displayExpected, modifier = Modifier.weight(1.5f), style = mono, maxLines = maxLines)
        Text(
            text = displayActual,
            modifier = Modifier.weight(1.5f),
            style = mono,
            color = if (!ungraded && !result.passed) palette.fail else textColor,
            maxLines = maxLines
        )
        Column(modifier = Modifier.width(160.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    label = statusLabel(result.status),
                    color = badgeColor,
                    modifier = Modifier.weight(1f)
                )
                if (isMultiline) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse row" else "Expand row",
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevronRotation),
                        tint = neutral
                    )
                }
            }
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

@Composable
private fun ErrorView(error: RuntimeError) {
    val palette = LocalEditorPalette.current
    val lines = error.stderr.lines().filter { it.isNotBlank() }
    val headline = lines.firstOrNull() ?: error.status
    val detail = lines.drop(1).joinToString("\n")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        StatusBadge(error.status, palette.fail)
        Spacer(Modifier.height(8.dp))
        Text(
            text = headline,
            style = TextStyle(
                fontFamily = CodeFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.fail
            )
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            CodeBlock("details", detail, labelColor = palette.fail)
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

// Strips absolute server paths from compiler output lines, e.g.:
//   /work/btcache/.../Main.java:4: error: ... → Main.java:4: error: ...
internal fun stripServerPaths(text: String): String =
    text.lines().joinToString("\n") { line ->
        line.replace(Regex("^/[^:]+/([^/]+:\\d+:)"), "$1")
    }

private val headerStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
)
