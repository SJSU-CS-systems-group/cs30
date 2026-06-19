package editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Web has no AWT cursor; show the column-resize affordance by setting the document cursor on
 * hover and clearing it on exit, so the divider reads as draggable (parity with desktop).
 */
actual fun Modifier.resizeCursorModifier(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter -> setBodyCursor("col-resize")
                PointerEventType.Exit -> setBodyCursor("")
            }
        }
    }
}

private fun setBodyCursor(value: String): Unit = js("{ document.body.style.cursor = value; }")
