package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime

// @Id = ipAddress (not a generated UUID) is deliberate: each lab device has a static IP,
// so one row per device is the right model. JpaRepository.save() upserts on this primary
// key automatically (Hibernate merge: UPDATE if a row with this IP exists, INSERT if not).
//
// ipAddress = request.remoteAddr, valid only because this deployment has no reverse proxy/CDN
// in front (embedded Tomcat terminates TLS directly — see application.properties server.ssl.*).
// If that ever changes, this needs server.forward-headers-strategy + a trusted-proxy check.
@Entity
@Table(name = "login_sessions")
data class LoginSession(
    @Id
    @Column(name = "ip_address")
    val ipAddress: String = "",
    @Column(name = "student_email")
    val studentEmail: String = "",
    val token: String = "",
    val platform: String = "",
    @Column(name = "logged_in_at")
    val loggedInAt: LocalDateTime = LocalDateTime.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoginSession) return false
        return ipAddress == other.ipAddress
    }

    override fun hashCode(): Int = ipAddress.hashCode()

    override fun toString(): String =
        "LoginSession(ipAddress=$ipAddress, studentEmail=$studentEmail, platform=$platform, loggedInAt=$loggedInAt)"
}
