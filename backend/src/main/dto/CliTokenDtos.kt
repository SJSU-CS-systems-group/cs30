package com.cs30.server.dto

/**
 * Response for the admin/TA "reveal my CLI token" endpoints (AdminController, TaController).
 * token is only non-null when this call just generated or reset the token - past that, only its
 * salted hash is stored server-side, so there's nothing left to reveal (see CliTokenService).
 */
data class CliTokenReveal(val token: String?)
