package editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

actual fun Modifier.resizeCursorModifier(axis: ResizeAxis): Modifier {
    val cursor = when (axis) {
        ResizeAxis.HORIZONTAL -> Cursor.E_RESIZE_CURSOR
        ResizeAxis.VERTICAL -> Cursor.N_RESIZE_CURSOR
    }
    return pointerHoverIcon(PointerIcon(Cursor(cursor)))
}
