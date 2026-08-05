package com.cs30.server.controller

import com.cs30.server.dto.CliTokenReveal
import com.cs30.server.service.AdminIdentityService
import com.cs30.server.service.CliTokenService
import com.cs30.server.service.TaIdentityService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Reveals/resets the admin's and each TA's own CLI token - same get-or-create-or-reset shape for
 * both (see CliTokenService), just gated by a different identity service. Used to live as one
 * endpoint apiece on AdminController/TaController; pulled out here since the two were identical
 * apart from which session type authenticates the call.
 *
 * Replaces the old scheme of embedding the raw token in the /admin/callback or /ta/callback
 * redirect URL (see AdminOAuthController/TaOAuthController): each dashboard now calls its endpoint
 * here itself once it has a valid session, so the token never touches a URL, browser history, or
 * referrer header. token in the response is null once it's no longer recoverable (only its hash is
 * stored past the first reveal) - the dashboard falls back to offering reset in that case.
 */
@RestController
class CliTokenController(
    private val cliTokenService: CliTokenService,
    private val adminIdentityService: AdminIdentityService,
    private val taIdentityService: TaIdentityService,
) {
    @PostMapping("/api/admin/cli-token")
    fun getAdminCliToken(
        @RequestParam("reset", required = false) reset: Boolean?,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<CliTokenReveal> {
        val email = adminIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val result = if (reset == true) {
            cliTokenService.resetAdminToken(email)
        } else {
            cliTokenService.getOrCreateAdminToken(email)
        }
        return ResponseEntity.ok(CliTokenReveal(token = result.rawToken))
    }

    @PostMapping("/api/ta/cli-token")
    fun getTaCliToken(
        @RequestParam("reset", required = false) reset: Boolean?,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<CliTokenReveal> {
        val email = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val result = if (reset == true) {
            cliTokenService.resetTaToken(email)
        } else {
            cliTokenService.getOrCreateTaToken(email)
        }
        return ResponseEntity.ok(CliTokenReveal(token = result.rawToken))
    }
}
