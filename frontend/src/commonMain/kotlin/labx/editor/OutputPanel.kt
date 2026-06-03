package labx.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import labx.data.RunOutput
import labx.data.RuntimeError
import labx.data.TestResult
import labx.data.TestResultsResponse
import labx.theme.FailRed
import labx.theme.PassGreen

sealed class OutputMode {
    data object Empty : OutputMode()
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
            .height(240.dp),
        shadowElevation = 8.dp,
        color = Color(0xFFF8F8F8)
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
                is OutputMode.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No output yet.", color = Color.Gray)
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
    val statusColor = when (output.status) {
        "SUCCESS" -> PassGreen
        else -> FailRed
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
                color = Color.Gray
            )
        }
        Spacer(Modifier.height(8.dp))
        if (output.stdout.isNotEmpty()) {
            CodeBlock("stdout", output.stdout)
        }
        if (output.stderr.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            CodeBlock("stderr", output.stderr, labelColor = FailRed)
        }
    }
}

@Composable
private fun TestResultsView(response: TestResultsResponse, isSubmit: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (isSubmit) {
            Text(
                text = "Submission saved locally for prototype.",
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
            Text("Result",   modifier = Modifier.width(60.dp),  style = headerStyle)
        }
        HorizontalDivider()

        LazyColumn {
            items(response.results) { result ->
                TestResultRow(result)
                HorizontalDivider(color = Color(0xFFEEEEEE))
            }
        }
    }
}

@Composable
private fun TestResultRow(result: TestResult) {
    val rowBg = if (result.passed) Color(0xFFF1FBF1) else Color(0xFFFFF1F1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${result.testCase}",
            modifier = Modifier.width(28.dp),
            style = monoStyle
        )
        Text(
            text = result.input,
            modifier = Modifier.weight(2f),
            style = monoStyle,
            maxLines = 2
        )
        Text(
            text = result.expectedOutput,
            modifier = Modifier.weight(1.5f),
            style = monoStyle
        )
        Text(
            text = result.actualOutput,
            modifier = Modifier.weight(1.5f),
            style = monoStyle,
            color = if (result.passed) Color(0xFF1C1C1C) else FailRed
        )
        StatusBadge(
            label = if (result.passed) "PASS" else "FAIL",
            color = if (result.passed) PassGreen else FailRed,
            modifier = Modifier.width(60.dp)
        )
    }
}

@Composable
private fun ErrorView(error: RuntimeError) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        StatusBadge(error.status, FailRed)
        Spacer(Modifier.height(8.dp))
        Text(
            text = error.stderr,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = FailRed
            )
        )
    }
}

@Composable
private fun CodeBlock(label: String, content: String, labelColor: Color = Color.Gray) {
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
                .background(Color(0xFFF0F0F0), shape = RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text(
                text = content,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFF1C1C1C)
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
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
    }
}

private val headerStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    color = Color(0xFF444444)
)

private val monoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    color = Color(0xFF1C1C1C)
)
