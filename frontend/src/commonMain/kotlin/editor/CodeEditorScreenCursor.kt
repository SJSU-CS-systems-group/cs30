package editor

import androidx.compose.ui.Modifier

enum class ResizeAxis { HORIZONTAL, VERTICAL }

expect fun Modifier.resizeCursorModifier(axis: ResizeAxis = ResizeAxis.HORIZONTAL): Modifier
