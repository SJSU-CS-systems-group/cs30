package lockdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/** Convenience overload for callers holding a [LockdownController]. */
@Composable
fun LockdownBanner(controller: LockdownController, modifier: Modifier = Modifier) {
    val presenter = remember(controller) {
        LockdownBannerPresenter(controller.violations, controller.active)
    }
    LockdownBanner(presenter, modifier)
}

/**
 * Presentation-only banner. All show/dismiss/clear logic lives in [LockdownBannerPresenter];
 * this just renders the current message. Place it in a Compose-only region the HTML problem
 * panel never covers (a strip under the top bar): heavyweight web/desktop HTML surfaces paint
 * above Compose, so an overlay over the panel would be occluded.
 */
@Composable
fun LockdownBanner(presenter: LockdownBannerPresenter, modifier: Modifier = Modifier) {
    LaunchedEffect(presenter) { presenter.run() }
    val message by presenter.current.collectAsState()
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        message?.let { BannerSurface(it) }
    }
}

@Composable
private fun BannerSurface(message: BannerMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = BANNER_H_PADDING, vertical = BANNER_V_PADDING),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Lockdown violation: ${message.label}" + (message.detail?.let { " — $it" } ?: ""),
            color = MaterialTheme.colorScheme.onError,
            fontWeight = FontWeight.SemiBold
        )
    }
}
