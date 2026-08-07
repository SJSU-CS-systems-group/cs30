package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Who a CLI token authorizes - the CLI's own version of a "role", unrelated to session identity. */
enum class CliTokenRole { ADMIN, PROFESSOR, TA }

/**
 * Long-lived credentials for running the CLI - distinct from LoginSession/TaSession, which are
 * short-lived, heartbeat-refreshed browser session tokens. Only a salted hash of the token is
 * stored, like a password - the raw value exists only at the moment it's generated (see
 * CliTokenService) and can never be recovered from this row afterward.
 */
@Entity
@Table(name = "cli_tokens", indexes = [Index(name = "idx_cli_tokens_role", columnList = "role")])
data class CliToken(
    @Id
    val id: String = UUID.randomUUID().toString(),
    val email: String = "",
    @Column(name = "token_hash")
    val tokenHash: String = "",
    val salt: String = "",
    @Enumerated(EnumType.STRING)
    val role: CliTokenRole = CliTokenRole.ADMIN,
    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CliToken) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
