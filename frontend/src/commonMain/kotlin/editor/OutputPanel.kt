package editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.RunOutput
import data.RuntimeError
import data.TestResult
import data.TestResultsResponse
import theme.CodeFont
import theme.Dims
import theme.LocalEditorPalette
import theme.MonoTextStyle

sealed class OutputMode {
    data object Empty : OutputMode()
    data object Loading : OutputMode()
    data class Run(val output: RunOutput) : OutputMode()
    data class Test(val response: TestResultsResponse, val isSubmit: Boolean) : OutputMode()
    data class Error(val error: RuntimeError) : OutputMode()
}

@Composable
fun OutputPanel(
    outputMode: OutputMode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(Dims.outputPanelHeight),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Output",
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
                            Text("Run your code to see output", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                is OutputMode.Run -> RunOutputView(outputMode.output)

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
private fun RunOutputView(output: RunOutput) {
    val palette = LocalEditorPalette.current
    val statusColor = when (output.status) {
        "SUCCESS" -> palette.pass
        else -> palette.fail
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(output.status, statusColor)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Execution time: ${output.executionTimeMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        if (output.stdout.isNotEmpty()) {
            CodeBlock("stdout", output.stdout)
        }
        if (output.stderr.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            CodeBlock("stderr", output.stderr, labelColor = palette.fail)
        }
    }
}

@Composable
private fun TestResultsView(response: TestResultsResponse, isSubmit: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (response.status.isNotBlank()) {
            Text(
                text = response.status,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider()
        }

        // Header row
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
    // No status = ungraded (custom case with no expected answer): show "Executed", not a verdict.
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
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
            Text(text = result.input, modifier = Modifier.weight(2f), style = mono, maxLines = 2)
        }
        Text(text = result.expectedOutput, modifier = Modifier.weight(1.5f), style = mono)
        Text(
            text = result.actualOutput,
            modifier = Modifier.weight(1.5f),
            style = mono,
            color = if (!ungraded && !result.passed) palette.fail else textColor
        )
        StatusBadge(
            label = statusLabel(result.status),
            color = badgeColor,
            modifier = Modifier.width(160.dp)
        )
    }
}

@Composable
private fun ErrorView(error: RuntimeError) {
    val palette = LocalEditorPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        StatusBadge(error.status, palette.fail)
        Spacer(Modifier.height(8.dp))
        Text(
            text = error.stderr,
            style = TextStyle(
                fontFamily = CodeFont,
                fontSize = 13.sp,
                color = palette.fail
            )
        )
    }
}

@Composable
private fun CodeBlock(label: String, content: String, labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
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

private val headerStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
)
