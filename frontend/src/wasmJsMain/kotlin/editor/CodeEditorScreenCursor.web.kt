package editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Web has no AWT cursor, and pointerHoverIcon can't help either: the only public PointerIcons on
 * wasm are Default/Crosshair/Text/Hand. So swap the cursor in CSS on hover. It has to be an
 * !important stylesheet rule rather than an inline or body style, because Compose writes
 * `cursor: default` inline on its own <canvas> and that would otherwise win.
 */
actual fun Modifier.resizeCursorModifier(axis: ResizeAxis): Modifier = pointerInput(axis) {
    val cursor = when (axis) {
        ResizeAxis.HORIZONTAL -> "ew-resize"
        ResizeAxis.VERTICAL -> "ns-resize"
    }
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter -> setResizeCursor(cursor)
                PointerEventType.Exit -> setResizeCursor("")
            }
        }
    }
}

private fun setResizeCursor(cursor: String): Unit = js(
    """{
        var el = document.getElementById('labxResizeCursor');
        if (!el) {
            el = document.createElement('style');
            el.id = 'labxResizeCursor';
            document.head.appendChild(el);
        }
        el.textContent = cursor
            ? 'canvas, body { cursor: ' + cursor + ' !important; }'
            : '';
    }"""
)
