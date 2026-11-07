package com.konecto.series76.core

/** Errors the UI can show. Every data-layer failure is mapped to one of these. */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No connection, timeout or server unreachable: the operation can be retried later. */
    class Network(cause: Throwable? = null) : AppError("Can't reach the server", cause)

    /** Missing/expired token: the app logs out. */
    class Unauthorized(message: String = "Your session expired. Please log in again") : AppError(message)

    /** The backend answered with its error format `{"error": {"code", "message"}}`. */
    class Api(val status: Int, val code: String, message: String) : AppError(message) {
        /** 5xx: the agent/LLM or DB failed; retrying the same client_message_id is safe. */
        val isServerSide: Boolean get() = status >= 500
    }

    class Unknown(cause: Throwable? = null) : AppError(cause?.message ?: "Unexpected error", cause)
}
