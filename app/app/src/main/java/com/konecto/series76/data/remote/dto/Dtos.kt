package com.konecto.series76.data.remote.dto

import com.konecto.series76.domain.model.ProductCard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Mirror of backend/app/api/schemas.py (contract: docs/api_contract.md).

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserDto,
)

@Serializable
data class ConversationRequest(
    @SerialName("session_id") val sessionId: String? = null,
    val query: String,
    @SerialName("client_message_id") val clientMessageId: String? = null,
)

@Serializable
data class ConversationResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("created_at") val createdAt: String,
    val answer: String,
    val status: String,
    val products: List<ProductCard> = emptyList(),
    val suggestions: List<String> = emptyList(),
)

@Serializable
data class SessionSummaryDto(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
)

@Serializable
data class SessionListDto(val items: List<SessionSummaryDto>, val total: Int)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val content: String,
    val status: String? = null,
    val products: List<ProductCard> = emptyList(),
    val suggestions: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SessionMessagesDto(
    @SerialName("session_id") val sessionId: String,
    val title: String,
    val messages: List<MessageDto>,
)

@Serializable
data class DebugStepDto(
    val type: String = "tool_call",
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val result: JsonElement? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class TokenUsageDto(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
data class GroundingDto(
    val ok: Boolean,
    @SerialName("unverified_numbers") val unverifiedNumbers: List<String> = emptyList(),
    val retried: Boolean = false,
)

@Serializable
data class DebugTraceDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("session_id") val sessionId: String,
    val model: String,
    @SerialName("latency_ms") val latencyMs: Long,
    val usage: TokenUsageDto = TokenUsageDto(),
    val steps: List<DebugStepDto> = emptyList(),
    val grounding: GroundingDto,
    @SerialName("structured_output") val structuredOutput: JsonObject? = null,
    val error: String? = null,
)

@Serializable
data class HealthDto(val status: String, val db: Boolean, val actuators: Int = 0)
