@file:OptIn(ExperimentalFoundationApi::class)

package editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import html.HtmlRenderer

private val DIVIDER_HIT_WIDTH = 8.dp     // generous hit/hover target
private val DIVIDER_VISUAL_WIDTH = 1.dp  // thin painted line centered in the hit area

/**
 * Draggable divider between the problem panel and the editor. Encapsulates the wider hit area,
 * the platform resize cursor, and — crucially on web — disabling the HTML surface's pointer
 * capture during the drag (via [HtmlRenderer.setInteractive]) so the gesture isn't swallowed.
 * The caller owns the width state and applies/clamps it via [onDrag].
 */
@Composable
fun ProblemPanelDivider(
    renderer: HtmlRenderer,
    onDrag: (delta: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(DIVIDER_HIT_WIDTH)
            .fillMaxHeight()
            .resizeCursorModifier()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { renderer.setInteractive(false) },
                    onDragEnd = { renderer.setInteractive(true) },
                    onDragCancel = { renderer.setInteractive(true) },
                    onHorizontalDrag = { _, dragAmount -> onDrag(dragAmount.toDp()) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(DIVIDER_VISUAL_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}
