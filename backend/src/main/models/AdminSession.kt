package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Session for the admin webpage itself - distinct from CliToken (which gates the CLI, not this
 * page). Same heartbeat-based mechanism as TaSession: the dashboard's heartbeat refreshes
 * lastHeartbeatAt periodically, and the session expires once it goes too long without one (10
 * minutes - a tighter TTL than TaSession's, see AdminIdentityService), not from a fixed TTL
 * counted from login.
 */
@Entity
@Table(name = "admin_sessions", indexes = [Index(name = "idx_admin_sessions_email", columnList = "email")])
data class AdminSession(
    @Id
    val token: String = "",
    val email: String = "",
    @Column(name = "ip_address")
    val ipAddress: String = "",
    @Column(name = "logged_in_at")
    val loggedInAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    @Column(name = "last_heartbeat_at")
    val lastHeartbeatAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdminSession) return false
        return token == other.token
    }

    override fun hashCode(): Int = token.hashCode()
}
