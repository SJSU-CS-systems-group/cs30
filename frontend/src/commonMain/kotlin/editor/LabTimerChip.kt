package editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private object TimerThresholds {
    const val TICK_MS    = 1_000L
    const val WARNING_MS = 15 * 60 * 1_000L
    const val URGENT_MS  =  5 * 60 * 1_000L
}

private object PulseSpec {
    const val DURATION_MS = 600
    const val MIN_ALPHA   = 0.45f
}

@Composable
fun LabTimerChip(remainingMs: Long?) {
    if (remainingMs == null) return

    var localMs by remember(remainingMs) { mutableStateOf(remainingMs) }

    LaunchedEffect(remainingMs) {
        localMs = remainingMs
        while (localMs > 0L) {
            delay(TimerThresholds.TICK_MS)
            localMs = maxOf(0L, localMs - TimerThresholds.TICK_MS)
        }
    }

    val isExpired = localMs <= 0L
    val isUrgent  = !isExpired && localMs <= TimerThresholds.URGENT_MS
    val isWarning = !isExpired && !isUrgent && localMs <= TimerThresholds.WARNING_MS

    val targetColor = when {
        isExpired || isUrgent -> MaterialTheme.colorScheme.error
        isWarning             -> MaterialTheme.colorScheme.tertiary
        else                  -> MaterialTheme.colorScheme.primary
    }
    val color by animateColorAsState(targetValue = targetColor, label = "timerColor")

    val infiniteTransition = rememberInfiniteTransition(label = "timerPulse")
    val chipAlpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = if (isUrgent) PulseSpec.MIN_ALPHA else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = PulseSpec.DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chipAlpha",
    )

    Surface(
        shape    = MaterialTheme.shapes.small,
        color    = color.copy(alpha = 0.10f),
        border   = BorderStroke(1.dp, color.copy(alpha = 0.40f)),
        modifier = Modifier.alpha(chipAlpha),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector        = Icons.Outlined.Timer,
                contentDescription = "Time remaining",
                tint               = color,
                modifier           = Modifier.size(15.dp),
            )
            Text(
                text  = if (isExpired) "Time's up" else formatRemaining(localMs),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isUrgent || isExpired) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = color,
            )
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val s   = ms / 1_000L
    val h   = s / 3_600L
    val m   = (s % 3_600L) / 60L
    val sec = s % 60L
    return if (h > 0L) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    else               "$m:${sec.toString().padStart(2, '0')}"
}
