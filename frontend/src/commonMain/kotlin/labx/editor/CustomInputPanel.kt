package labx.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun CustomInputPanel(
    current: String,
    onCurrentChange: (String) -> Unit,
    cases: List<String>,
    onAddCase: () -> Unit,
    onRemoveCase: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = current,
                onValueChange = onCurrentChange,
                label = { Text("Custom Input (stdin)") },
                placeholder = { Text("Type stdin for Run, or click Add to queue a test case") },
                singleLine = false,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 88.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onAddCase,
                enabled = current.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Add test case") }
        }

        if (cases.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Custom test cases (${cases.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cases.forEachIndexed { index, case ->
                    CustomCaseRow(
                        index = index + 1,
                        text = case,
                        onRemove = { onRemoveCase(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomCaseRow(index: Int, text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$index",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF666666),
            ),
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFF1C1C1C),
            ),
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Text("✕", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB00020))
        }
    }
}
