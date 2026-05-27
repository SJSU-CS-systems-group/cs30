package labx.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import labx.html.HtmlText

@Composable
fun ProblemPanel(html: String, modifier: Modifier = Modifier, interactive: Boolean = true) {
    Box(
        modifier = modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HtmlText(
            html = html,
            modifier = Modifier.fillMaxSize(),
            interactive = interactive
        )
    }
}
