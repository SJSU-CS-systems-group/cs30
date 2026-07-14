package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * TA login sessions - simpler than student sessions since TAs don't need:
 * - Single-session enforcement
 * - Heartbeat/TTL tracking
 * - Logout tracking
 */
@Entity
@Table(name = "ta_sessions", indexes = [Index(name = "idx_ta_sessions_email", columnList = "email")])
data class TaSession(
    @Id
    val token: String = "",
    val email: String = "",
    @Column(name = "ip_address")
    val ipAddress: String = "",
    @Column(name = "logged_in_at")
    val loggedInAt: LocalDateTime = LocalDateTime.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaSession) return false
        return token == other.token
    }

    override fun hashCode(): Int = token.hashCode()
}
