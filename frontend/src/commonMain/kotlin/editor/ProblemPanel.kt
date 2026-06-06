package editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import html.HtmlRenderer
import html.HtmlText

@Composable
fun ProblemPanel(
    html: String,
    css: String = "",
    renderer: HtmlRenderer,
    modifier: Modifier = Modifier.width(320.dp),
    interactive: Boolean = true,
    isLoading: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HtmlText(
            html = html,
            css = css,
            renderer = renderer,
            modifier = Modifier.fillMaxSize(),
            interactive = interactive
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
