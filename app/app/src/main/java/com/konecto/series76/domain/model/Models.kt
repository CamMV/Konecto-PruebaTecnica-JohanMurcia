package com.konecto.series76.domain.model

/** [isAdmin]: can open the debug screens (the backend also enforces it: 403 for regular users). */
data class User(val id: String, val email: String, val name: String, val isAdmin: Boolean = false)

data class AuthSession(val token: String, val expiresAtMillis: Long, val user: User)

/** `status` of an agent answer (contract: docs/api_contract.md). */
enum class AnswerStatus(val wire: String, val label: String) {
    ANSWERED("answered", "Answered"),
    CLARIFICATION_NEEDED("clarification_needed", "Needs clarification"),
    NOT_FOUND("not_found", "Part not found"),
    NO_MATCH("no_match", "No model matches"),
    DATA_UNAVAILABLE("data_unavailable", "No data in datasheet"),
    OUT_OF_SCOPE("out_of_scope", "Out of scope");

    companion object {
        fun fromWire(value: String?): AnswerStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Local sync state of a conversation (offline-first). */
enum class SyncState { SYNCED, PENDING_CREATE, PENDING_DELETE }

/** Local send state of a message. Assistant messages are always SENT (they come from the server). */
enum class SendState { SENT, PENDING, FAILED }

enum class Role { USER, ASSISTANT }

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val lastPreview: String?,
    val syncState: SyncState,
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val status: AnswerStatus?,
    val products: List<ProductCard>,
    val suggestions: List<String>,
    val createdAt: Long,
    val sendState: SendState,
    val clientMessageId: String?,
    val errorMessage: String?,
)

data class ServerHealth(val ok: Boolean, val dbUp: Boolean, val actuators: Int)
