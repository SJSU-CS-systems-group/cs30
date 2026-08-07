package com.cs30.server.service

import com.cs30.server.models.CliToken
import com.cs30.server.models.CliTokenRole
import com.cs30.server.repository.CliTokenRepository
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(CliTokenService::class.java)

    /**
     * Exactly one admin token ever exists, created on first successful /admin login. Every login
     * after that reuses the same row, but its raw value can no longer be recovered (only its hash
     * is stored) - rawToken is null on every call past the first.
     */
    fun getOrCreateAdminToken(email: String): AdminTokenResult {
        cliTokenRepository.findFirstByRole(CliTokenRole.ADMIN)?.let { return AdminTokenResult(it, rawToken = null) }
        return createToken(email, CliTokenRole.ADMIN)
    }

    /** Explicitly invalidates whatever admin token exists (e.g. it was lost) and mints a fresh one. */
    fun resetAdminToken(email: String): AdminTokenResult {
        cliTokenRepository.findFirstByRole(CliTokenRole.ADMIN)?.let { cliTokenRepository.delete(it) }
        log.info("[cli-token] admin token reset by {}", email)
        return createToken(email, CliTokenRole.ADMIN)
    }

    /**
     * Gates running the CLI at all - null unless the candidate hashes to match some stored token,
     * of any role. Callers that need to know *which* commands that role may run (see
     * CliApplication) look at the returned CliToken.role themselves; this only answers "is this a
     * real token."
     *
     * Every row's salt differs, so there's no indexed lookup by the raw candidate alone (unlike
     * the DB-primary-key style lookups elsewhere) - this scans all tokens and hashes the candidate
     * against each one's salt. Fine at this table's size (one admin + a handful of TAs).
     */
    fun resolveToken(candidate: String): CliToken? {
        if (candidate.isBlank()) return null
        return cliTokenRepository.findAll().firstOrNull { hash(candidate, it.salt) == it.tokenHash }
    }

    /**
     * Unlike the admin token (one system-wide), each TA has their own - looked up by email, not
     * just role. Created on that TA's first /ta login, reused after that.
     */
    fun getOrCreateTaToken(email: String): AdminTokenResult {
        cliTokenRepository.findFirstByEmailAndRole(email, CliTokenRole.TA)?.let { return AdminTokenResult(it, rawToken = null) }
        return createToken(email, CliTokenRole.TA)
    }

    /** Explicitly invalidates this TA's existing token (e.g. it was lost) and mints a fresh one. */
    fun resetTaToken(email: String): AdminTokenResult {
        cliTokenRepository.findFirstByEmailAndRole(email, CliTokenRole.TA)?.let { cliTokenRepository.delete(it) }
        log.info("[cli-token] TA token reset by {}", email)
        return createToken(email, CliTokenRole.TA)
    }

    private fun createToken(email: String, role: CliTokenRole): AdminTokenResult {
        val rawToken = UUID.randomUUID().toString()
        val salt = generateSalt()
        val saved = cliTokenRepository.save(
            CliToken(email = email, tokenHash = hash(rawToken, salt), salt = salt, role = role)
        )
        log.info("[cli-token] generated {} token for {}", role, email)
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
