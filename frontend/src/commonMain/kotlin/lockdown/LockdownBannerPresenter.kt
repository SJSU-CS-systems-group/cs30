package lockdown

import data.LockdownViolation
import data.ViolationKind
import data.ViolationSeverity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** A violation ready for display: its banner [label] and optional [detail]. */
data class BannerMessage(val label: String, val detail: String?)

/**
 * Owns the violation-banner display logic so the Composable stays presentation-only:
 *  - shows the latest ALERT-severity violation (`collectLatest` cancels the prior dismiss timer),
 *  - auto-dismisses after [autoDismiss],
 *  - clears immediately when lockdown becomes inactive (the lab ended).
 *
 * Depends only on the two flows (not the whole controller), so it is trivially unit-testable.
 */
class LockdownBannerPresenter(
    private val violations: SharedFlow<LockdownViolation>,
    private val active: StateFlow<Boolean>,
    private val autoDismiss: Duration = BANNER_AUTO_DISMISS,
) {
    private val _current = MutableStateFlow<BannerMessage?>(null)
    val current: StateFlow<BannerMessage?> = _current.asStateFlow()

    /** Drives the banner; launch once (e.g. from a `LaunchedEffect`). Runs until cancelled. */
    suspend fun run(): Unit = coroutineScope {
        launch { active.collect { isActive -> if (!isActive) _current.value = null } }
        // Filter to ALERT severity BEFORE collectLatest: INFO events (FocusGained, Heartbeat,
        // lifecycle, …) must not enter the collector, or they'd cancel a showing banner's
        // dismiss timer and strand it. FocusLoss is always followed by FocusGained — exactly
        // why "window lost focus" never auto-dismissed before.
        violations
            .filter { it.kind.severity == ViolationSeverity.ALERT }
            .collectLatest { violation ->
                // The PasteFromOutside detail carries the pasted content snippet — that is for the
                // activity log only, not for the on-screen banner. Show the label alone for it.
                val bannerDetail =
                    if (violation.kind == ViolationKind.PasteFromOutside) null else violation.detail
                _current.value = BannerMessage(violationLabel(violation.kind), bannerDetail)
                delay(autoDismiss)
                _current.value = null
            }
    }
}
