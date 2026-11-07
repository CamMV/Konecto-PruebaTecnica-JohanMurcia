package com.konecto.series76.domain.model

/** Agent trace of one answer (GET /api/messages/{id}/debug), ready for the debug screen. */
data class DebugTrace(
    val messageId: String,
    val sessionId: String,
    val model: String,
    val latencyMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val steps: List<DebugStep>,
    val groundingOk: Boolean,
    val unverifiedNumbers: List<String>,
    val groundingRetried: Boolean,
    /** Pretty-printed JSON of the agent's structured output (AgentAnswer), if any. */
    val structuredOutputJson: String?,
    val structuredStatus: String?,
    val structuredPartNumbers: List<String>,
    val error: String?,
)

data class DebugStep(
    val name: String,
    val argsJson: String,
    val resultJson: String?,
    val durationMs: Long?,
    val error: String?,
    /** Short description of the rows the tool returned (e.g. "exact · 1 row: 761A00-11300000/A"). */
    val retrievedSummary: String,
)
