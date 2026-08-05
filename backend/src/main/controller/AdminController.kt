package com.cs30.server.controller

import com.cs30.server.dto.AdminCheckSessionResponse
import com.cs30.server.dto.AdminCliTokenInfo
import com.cs30.server.models.CliTokenRole
import com.cs30.server.repository.CliTokenRepository
import com.cs30.server.service.AdminIdentityService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Manage CLI tokens from the admin webpage - gated by AdminIdentityService, not CliToken itself.
 * The admin token itself is excluded/protected from the list/delete endpoints below - it's the
 * credential this whole page runs on top of, and there's only ever one, so it gets its own
 * reveal/reset endpoint (CliTokenController) instead of appearing in the CLI-tokens table here.
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminIdentityService: AdminIdentityService,
    private val cliTokenRepository: CliTokenRepository,
) {
    @GetMapping("/cli-tokens")
    fun listCliTokens(@RequestHeader("Authorization", required = false) authHeader: String?): ResponseEntity<List<AdminCliTokenInfo>> {
        if (adminIdentityService.resolve(authHeader) == null) {
            return ResponseEntity.status(401).build()
        }
        val tokens = cliTokenRepository.findAll()
            .filter { it.role != CliTokenRole.ADMIN }
            .map { AdminCliTokenInfo(id = it.id, email = it.email, role = it.role.name) }
        return ResponseEntity.ok(tokens)
    }

    @DeleteMapping("/cli-tokens/{id}")
    fun deleteCliToken(
        @PathVariable id: String,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<Void> {
        if (adminIdentityService.resolve(authHeader) == null) {
            return ResponseEntity.status(401).build()
        }
        val token = cliTokenRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        if (token.role == CliTokenRole.ADMIN) {
            return ResponseEntity.status(403).build()
        }
        cliTokenRepository.deleteById(id)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/logout")
    fun logout(@RequestHeader("Authorization", required = false) authHeader: String?): ResponseEntity<Void> {
        val token = adminIdentityService.token(authHeader)
        if (token.isNotBlank()) {
            adminIdentityService.revokeToken(token)
        }
        return ResponseEntity.ok().build()
    }

    /**
     * The admin dashboard's heartbeat (called every 2 minutes - tighter cadence than the TA
     * dashboard's /api/ta/check-session, since this session gates the CLI admin token) -
     * refreshes the session's TTL, or reports it expired after 10 minutes without one.
     */
    @GetMapping("/check-session")
    fun checkSession(@RequestHeader("Authorization", required = false) authHeader: String?): ResponseEntity<AdminCheckSessionResponse> {
        val token = adminIdentityService.token(authHeader)
        val email = if (token.isNotBlank()) adminIdentityService.refreshSession(token) else null
        return ResponseEntity.ok(AdminCheckSessionResponse(hasActiveSession = email != null, email = email))
    }
}
