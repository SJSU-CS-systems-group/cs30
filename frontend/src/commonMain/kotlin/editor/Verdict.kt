package editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import theme.LocalEditorPalette

// Judge verdict codes (kt-judge's Status enum): AC, WA, TLE, RTE, MLE, CE, JE.
// One mapping shared by every place that shows a verdict, so the colours can't drift
// apart the way they did when each screen kept its own copy.

/** Verdict colour. Unknown/null/JE stay neutral — they aren't a pass or a fail. */
@Composable
fun verdictColor(status: String?): Color = when (status) {
    "AC" -> LocalEditorPalette.current.pass
    "WA", "CE", "RTE" -> LocalEditorPalette.current.fail
    "TLE", "MLE" -> LocalEditorPalette.current.warning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Short verdict label, for chips and table cells. OutputPanel uses its own long form. */
fun verdictShortLabel(status: String): String = when (status) {
    "AC" -> "Accepted"
    "WA" -> "Wrong Answer"
    "TLE" -> "Time Limit"
    "CE" -> "Compile Error"
    "RTE" -> "Runtime Error"
    "MLE" -> "Memory Limit"
    else -> status
}
