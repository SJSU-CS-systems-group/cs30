package data

import kotlinx.serialization.Serializable

/**
 * Response for the admin/TA "reveal my CLI token" endpoints (AdminController.getCliToken,
 * TaController.getCliToken). token is only non-null right after it was just generated or reset -
 * past that only a salted hash is stored server-side, so there's nothing left to reveal.
 */
@Serializable
data class CliTokenReveal(val token: String? = null)
