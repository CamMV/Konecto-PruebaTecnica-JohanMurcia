package com.konecto.series76.data.mapper

import com.konecto.series76.core.DateFormats
import com.konecto.series76.data.local.db.MessageEntity
import com.konecto.series76.data.local.db.SessionEntity
import com.konecto.series76.data.remote.dto.ConversationResponse
import com.konecto.series76.data.remote.dto.DebugStepDto
import com.konecto.series76.data.remote.dto.DebugTraceDto
import com.konecto.series76.data.remote.dto.MessageDto
import com.konecto.series76.data.remote.dto.SessionSummaryDto
import com.konecto.series76.domain.model.AnswerStatus
import com.konecto.series76.domain.model.ChatMessage
import com.konecto.series76.domain.model.ChatSession
import com.konecto.series76.domain.model.DebugStep
import com.konecto.series76.domain.model.DebugTrace
import com.konecto.series76.domain.model.ProductCard
import com.konecto.series76.domain.model.Role
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Pure mapping functions (no I/O). Called from Dispatchers.Default by the repositories. */
@Singleton
class Mappers @Inject constructor(private val json: Json) {

    private val pretty = Json(json) { prettyPrint = true }
    private val products = ListSerializer(serializer<ProductCard>())
    private val strings = ListSerializer(String.serializer())

    // ------------------------------------------------------------------ API → Room

    fun sessionEntity(dto: SessionSummaryDto, userId: String) = SessionEntity(
        id = dto.id,
        userId = userId,
        title = dto.title,
        createdAt = DateFormats.parseIso(dto.createdAt),
        updatedAt = DateFormats.parseIso(dto.updatedAt),
        messageCount = dto.messageCount,
        lastPreview = dto.lastMessagePreview,
        syncState = SyncState.SYNCED,
    )

    fun messageEntity(dto: MessageDto, sessionId: String) = MessageEntity(
        id = dto.id,
        sessionId = sessionId,
        role = if (dto.role == "user") Role.USER else Role.ASSISTANT,
        content = dto.content,
        status = dto.status,
        productsJson = json.encodeToString(products, dto.products),
        suggestionsJson = json.encodeToString(strings, dto.suggestions),
        createdAt = DateFormats.parseIso(dto.createdAt),
        clientMessageId = null,
        sendState = SendState.SENT,
        errorMessage = null,
    )

    fun assistantEntity(response: ConversationResponse) = MessageEntity(
        id = response.messageId,
        sessionId = response.sessionId,
        role = Role.ASSISTANT,
        content = response.answer,
        status = response.status,
        productsJson = json.encodeToString(products, response.products),
        suggestionsJson = json.encodeToString(strings, response.suggestions),
        createdAt = DateFormats.parseIso(response.createdAt),
        clientMessageId = null,
        sendState = SendState.SENT,
        errorMessage = null,
    )

    fun pendingUserEntity(id: String, sessionId: String, text: String, now: Long) = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = Role.USER,
        content = text,
        status = null,
        productsJson = EMPTY_LIST,
        suggestionsJson = EMPTY_LIST,
        createdAt = now,
        clientMessageId = id,
        sendState = SendState.PENDING,
        errorMessage = null,
    )

    // ------------------------------------------------------------------ Room → domain

    fun session(e: SessionEntity) = ChatSession(
        id = e.id,
        title = e.title,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        messageCount = e.messageCount,
        lastPreview = e.lastPreview,
        syncState = e.syncState,
    )

    fun message(e: MessageEntity) = ChatMessage(
        id = e.id,
        sessionId = e.sessionId,
        role = e.role,
        content = e.content,
        status = AnswerStatus.fromWire(e.status),
        products = if (e.productsJson == EMPTY_LIST) emptyList() else json.decodeFromString(products, e.productsJson),
        suggestions = if (e.suggestionsJson == EMPTY_LIST) emptyList() else json.decodeFromString(strings, e.suggestionsJson),
        createdAt = e.createdAt,
        sendState = e.sendState,
        clientMessageId = e.clientMessageId,
        errorMessage = e.errorMessage,
    )

    // ------------------------------------------------------------------ debug

    fun encodeTrace(dto: DebugTraceDto): String = json.encodeToString(serializer<DebugTraceDto>(), dto)

    fun decodeTrace(raw: String): DebugTraceDto = json.decodeFromString(serializer<DebugTraceDto>(), raw)

    fun trace(dto: DebugTraceDto): DebugTrace {
        val so = dto.structuredOutput
        return DebugTrace(
            messageId = dto.messageId,
            sessionId = dto.sessionId,
            model = dto.model,
            latencyMs = dto.latencyMs,
            inputTokens = dto.usage.inputTokens,
            outputTokens = dto.usage.outputTokens,
            steps = dto.steps.map(::step),
            groundingOk = dto.grounding.ok,
            unverifiedNumbers = dto.grounding.unverifiedNumbers,
            groundingRetried = dto.grounding.retried,
            structuredOutputJson = so?.let { pretty.encodeToString(JsonElement.serializer(), it) },
            structuredStatus = (so?.get("status") as? JsonPrimitive)?.contentOrNull,
            structuredPartNumbers = (so?.get("part_numbers") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            error = dto.error,
        )
    }

    private fun step(s: DebugStepDto) = DebugStep(
        name = s.name,
        argsJson = pretty.encodeToString(JsonElement.serializer(), s.args),
        resultJson = s.result?.let { pretty.encodeToString(JsonElement.serializer(), it) },
        durationMs = s.durationMs,
        error = s.error,
        retrievedSummary = summarize(s.result),
    )

    /** "exact · 1 row: 761A00-11300000/A" from a tool result ({match_type, results: [{base_part_number…}]}). */
    internal fun summarize(result: JsonElement?): String {
        val obj = result as? JsonObject
        val rows = when (result) {
            is JsonArray -> result
            is JsonObject -> result["results"] as? JsonArray
            else -> null
        } ?: return if (result == null) "No result" else "Non-tabular result"
        val parts = rows.mapNotNull { (it as? JsonObject)?.get("base_part_number")?.jsonPrimitive?.contentOrNull }
        val matchType = (obj?.get("match_type") as? JsonPrimitive)?.contentOrNull
        val head = buildString {
            if (matchType != null) append(matchType).append(" · ")
            append(rows.size).append(if (rows.size == 1) " row" else " rows")
        }
        if (parts.isEmpty()) return head
        val shown = parts.take(MAX_PARTS).joinToString(", ")
        val more = if (parts.size > MAX_PARTS) " (+${parts.size - MAX_PARTS} more)" else ""
        return "$head: $shown$more"
    }

    private companion object {
        const val EMPTY_LIST = "[]"
        const val MAX_PARTS = 6
    }
}
