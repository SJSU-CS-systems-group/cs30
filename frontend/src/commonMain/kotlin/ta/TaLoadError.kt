package ta

/**
 * Why a TA dashboard request failed, with the fixed text the UI shows for it. Messages are
 * client-side only: they're chosen from the HTTP status, and no server response text is ever shown.
 */
enum class TaLoadError(val message: String) {
    SESSION_EXPIRED("Your session has expired. Please log in again."),
    FORBIDDEN("You don't have access to this lab."),
    NOT_FOUND("This problem isn't available in this lab anymore."),
    SERVER_ERROR("The server couldn't load this problem. Please try again later."),
    NETWORK("Can't reach the server. Check your connection and try again."),
    UNEXPECTED("Something went wrong while loading this problem.");

    companion object {
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN_STATUS = 403
        private const val NOT_FOUND_STATUS = 404
        private val SERVER_ERRORS = 500..599

        fun fromStatus(status: Int): TaLoadError = when (status) {
            UNAUTHORIZED -> SESSION_EXPIRED
            FORBIDDEN_STATUS -> FORBIDDEN
            NOT_FOUND_STATUS -> NOT_FOUND
            in SERVER_ERRORS -> SERVER_ERROR
            else -> UNEXPECTED
        }
    }
}

/** A TA dashboard request's outcome: the value, or which [TaLoadError] to show. */
sealed interface TaLoadResult<out T> {
    data class Success<T>(val value: T) : TaLoadResult<T>
    data class Failure(val error: TaLoadError) : TaLoadResult<Nothing>
}
