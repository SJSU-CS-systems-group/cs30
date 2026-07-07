package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime

// @Id = token (not ipAddress): this table is the actual session store, not just a per-device
// snapshot — every login inserts a new row and old rows are kept (loggedOutAt marks when one
// ended), so a device's IP can no longer be unique across rows. Indexed by student_email since
// every login/heartbeat/IP lookup now queries by it and the table grows with history, not upserts.
//
// ipAddress = request.remoteAddr, valid only because this deployment has no reverse proxy/CDN
// in front (embedded Tomcat terminates TLS directly — see application.properties server.ssl.*).
// If that ever changes, this needs server.forward-headers-strategy + a trusted-proxy check.
@Entity
@Table(name = "login_sessions", indexes = [Index(name = "idx_login_sessions_student_email", columnList = "student_email")])
data class LoginSession(
    @Id
    val token: String = "",
    @Column(name = "student_email")
    val studentEmail: String = "",
    @Column(name = "ip_address")
    val ipAddress: String = "",
    val platform: String = "",
    @Column(name = "logged_in_at")
    val loggedInAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "last_heartbeat_at")
    val lastHeartbeatAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "logged_out_at")
    val loggedOutAt: LocalDateTime? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoginSession) return false
        return token == other.token
    }

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String =
        "LoginSession(studentEmail=$studentEmail, platform=$platform, loggedInAt=$loggedInAt, lastHeartbeatAt=$lastHeartbeatAt, loggedOutAt=$loggedOutAt)"
}
