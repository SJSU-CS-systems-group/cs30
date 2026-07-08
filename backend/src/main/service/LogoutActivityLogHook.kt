package com.cs30.server.service

import data.LockdownViolation
import data.ViolationKind
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Runs before every session actually ends (explicit logout or TTL expiry): records a LoggedOut
 * lockdown event, then commits that day's activity log. Neither call catches its own failures —
 * unlike ActivityController's endpoints, which do — so a git failure here propagates back through
 * ApiTokenStore.endSession and blocks the logout instead of silently succeeding anyway.
 */
@Component
class LogoutActivityLogHook(
    private val activityLogService: ActivityLogService,
) {
    @EventListener
    fun onLogout(event: LogoutEvent) {
        val violation = LockdownViolation(
            kind = ViolationKind.LoggedOut,
            timestampMs = System.currentTimeMillis(),
            detail = event.reason,
        )
        activityLogService.recordEvent(
            studentEmail = event.session.studentEmail,
            token = event.session.token,
            problem = "",
            violation = violation,
            platform = event.session.platform,
        )
        activityLogService.commitSession(event.session.studentEmail)
    }
}
