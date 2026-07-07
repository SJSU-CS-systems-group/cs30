package com.cs30.server.service

import com.cs30.server.models.LoginSession

/**
 * Published by [ApiTokenStore] right before loggedOutAt is persisted — synchronously, so a
 * listener that throws blocks the logout (loggedOutAt is never written) rather than just
 * observing it.
 */
data class LogoutEvent(
    val session: LoginSession,
    val reason: String,
)
