package auth

import java.net.HttpURLConnection
import java.net.URI

/** Outcome of the pre-startup kiosk attestation probe. */
enum class KioskGateStatus {
    /** The request cleared the kiosk gate, or the gate is switched off. */
    ALLOWED,

    /** The kiosk gate rejected us — this process has no valid attestation. */
    BLOCKED,

    /** Could not tell. Start the app anyway and let its normal error handling take over. */
    UNKNOWN
}

/**
 * Asks the backend, before the UI starts, whether this process can get past the kiosk gate.
 *
 * Without this the app half-loads and then fails on every action with an opaque message — a student
 * sees "Failed to load problems: HTTP 403", which reads like a server fault rather than "you did not
 * launch this from the lab shortcut". One dialog up front is far more actionable.
 *
 * The probe deliberately sends no Authorization header, because the two checks are independent and
 * that is what makes the answer readable:
 *  - 403 with the kiosk marker -> the kiosk gate rejected us
 *  - 401                       -> we cleared the gate and only the normal auth check turned us away
 *
 * [UNKNOWN] is the fail-safe answer: a network outage, a changed endpoint, or a 403 from
 * `IpWhitelistFilter` (which answers with an HTML page, not the marker) must never stop a legitimate
 * student from starting the app. Only an unambiguous kiosk rejection does.
 */
object KioskGateCheck {

    /** Shown when the gate blocks startup. Flows from `cs30.kiosk.desktop-blocked-message`. */
    val blockedMessage: String =
        System.getProperty(BLOCKED_MESSAGE_PROPERTY, DEFAULT_BLOCKED_MESSAGE)

    fun probe(): KioskGateStatus =
        runCatching {
            val connection = openProbeConnection()
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                connection.errorStream?.bufferedReader()?.readText().orEmpty()
            } else {
                ""
            }
            connection.disconnect()
            classify(status, body)
        }.getOrDefault(KioskGateStatus.UNKNOWN)

    private fun openProbeConnection(): HttpURLConnection {
        val url = URI(AuthConfigDesktop.BACKEND_BASE_URL + PROBE_PATH).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = AuthConfigDesktop.BACKEND_CHECK_TIMEOUT_MS
            readTimeout = AuthConfigDesktop.BACKEND_CHECK_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            KioskSecretDesktop.value?.let { setRequestProperty(KioskSecretDesktop.headerName, it) }
        }
    }

    private fun classify(status: Int, body: String): KioskGateStatus = when {
        status == HttpURLConnection.HTTP_FORBIDDEN && body.contains(KIOSK_REJECT_MARKER) ->
            KioskGateStatus.BLOCKED
        status == HttpURLConnection.HTTP_UNAUTHORIZED -> KioskGateStatus.ALLOWED
        else -> KioskGateStatus.UNKNOWN
    }

    /** Gated, and answers an unauthenticated caller with 401 — which is what makes it a usable probe. */
    private const val PROBE_PATH = "/api/code/queue-status"

    /** The plain-text body KioskGateFilter returns to a non-HTML caller. */
    private const val KIOSK_REJECT_MARKER = "kiosk_required"

    private const val BLOCKED_MESSAGE_PROPERTY = "cs30.kiosk.desktopBlockedMessage"
    private const val DEFAULT_BLOCKED_MESSAGE =
        "CS30 must be launched using the CS30 shortcut on the lab workstation.\n\n" +
            "Contact your instructor if you believe this is an error."
}
