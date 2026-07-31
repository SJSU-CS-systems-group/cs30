package com.cs30.server.service

import com.cs30.server.models.CliToken
import com.cs30.server.models.CliTokenRole
import com.cs30.server.repository.CliTokenRepository
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** rawToken is only non-null when this call just generated a brand new token - never recoverable after. */
data class AdminTokenResult(val cliToken: CliToken, val rawToken: String?)

/**
 * Issues CLI tokens - these gate who's allowed to run the CLI at all, separate from
 * ApiTokenStore/TaIdentityService's browser session tokens. Only a salted hash is ever persisted,
 * so the raw token exists only in memory at generation time and in whatever the admin copied down.
 */
@Component
class CliTokenService(
    private val cliTokenRepository: CliTokenRepository,
) {
    /**
     * Exactly one admin token ever exists, created on first successful /admin login. Every login
     * after that reuses the same row, but its raw value can no longer be recovered (only its hash
     * is stored) - rawToken is null on every call past the first.
     */
    fun getOrCreateAdminToken(email: String): AdminTokenResult {
        cliTokenRepository.findFirstByRole(CliTokenRole.ADMIN)?.let { return AdminTokenResult(it, rawToken = null) }
        return createAdminToken(email)
    }

    /** Explicitly invalidates whatever admin token exists (e.g. it was lost) and mints a fresh one. */
    fun resetAdminToken(email: String): AdminTokenResult {
        cliTokenRepository.findFirstByRole(CliTokenRole.ADMIN)?.let { cliTokenRepository.delete(it) }
        return createAdminToken(email)
    }

    /** Gates running CLI commands - null unless the candidate hashes to match the stored admin token. */
    fun resolveAdminToken(candidate: String): CliToken? {
        if (candidate.isBlank()) return null
        val adminToken = cliTokenRepository.findFirstByRole(CliTokenRole.ADMIN) ?: return null
        return adminToken.takeIf { hash(candidate, it.salt) == it.tokenHash }
    }

    private fun createAdminToken(email: String): AdminTokenResult {
        val rawToken = UUID.randomUUID().toString()
        val salt = generateSalt()
        val saved = cliTokenRepository.save(
            CliToken(email = email, tokenHash = hash(rawToken, salt), salt = salt, role = CliTokenRole.ADMIN)
        )
        return AdminTokenResult(saved, rawToken = rawToken)
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun hash(rawToken: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.getDecoder().decode(salt))
        return Base64.getEncoder().encodeToString(digest.digest(rawToken.toByteArray(Charsets.UTF_8)))
    }
}
