package com.konecto.series76.domain.repository

import com.konecto.series76.domain.model.AuthSession
import com.konecto.series76.domain.model.ChatMessage
import com.konecto.series76.domain.model.ChatSession
import com.konecto.series76.domain.model.DebugSession
import com.konecto.series76.domain.model.DebugTrace
import com.konecto.series76.domain.model.ServerHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    /** Current session (null = logged out). Loaded from DataStore at startup. */
    val session: StateFlow<AuthSession?>

    /** Emits when the server rejected the token (401) and the app was logged out. */
    val forcedLogout: SharedFlow<Unit>

    /** Waits until the persisted session has been read (used by the splash screen). */
    suspend fun awaitLoaded(): AuthSession?

    suspend fun login(email: String, password: String): Result<AuthSession>

    /** Checks the saved token with /auth/me when there is connectivity (401 → logout). */
    suspend fun validateToken()

    /** Logs out and clears the local cache (chats belong to the user). */
    suspend fun logout()
}

interface ServerConfigRepository {
    /** Base URL of the backend (runtime state, persisted). Always ends with "/". */
    val baseUrl: StateFlow<String>

    /** Normalizes user input ("10.0.2.2:8001" → "http://10.0.2.2:8001/"); null if invalid. */
    fun normalize(input: String): String?

    /** GET /api/health against [url] without saving it. */
    suspend fun testConnection(url: String): Result<ServerHealth>

    /** Saves the URL. If it changed: logout + clear the cache (the data belongs to the other server). */
    suspend fun setBaseUrl(url: String): Result<Unit>
}

/**
 * Chats with cache-then-network: `observe*` read Room (instant, works offline) and `refresh*` update Room from
 * the API. Writes are local first and synced by the outbox (see sync/OutboxProcessor).
 */
interface ChatRepository {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeSession(sessionId: String): Flow<ChatSession?>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>

    /** Number of messages written offline that are still waiting to be sent. */
    fun observePendingCount(): Flow<Int>

    /** Ids of the messages being sent right now (the chat shows "typing…"). */
    val sendingMessageIds: StateFlow<Set<String>>

    suspend fun refreshSessions(): Result<Unit>
    suspend fun refreshMessages(sessionId: String): Result<Unit>

    /** Saves the message as PENDING (creating the conversation if [sessionId] is null) and asks for a sync. */
    suspend fun sendMessage(sessionId: String?, text: String): String

    suspend fun retryMessage(messageId: String)

    /** Hides the conversation right away and deletes it on the server when there is connectivity. */
    suspend fun deleteSession(sessionId: String)
}

interface DebugRepository {
    /** Every user's chats (admin, GET /api/debug/sessions), newest first. Network only. */
    suspend fun sessions(): Result<List<DebugSession>>

    /** History of any user's chat (GET /api/debug/sessions/{id}/messages); kept in memory for the detail screen. */
    suspend fun messages(sessionId: String): Result<List<ChatMessage>>

    /** Last history loaded by [messages] for that chat, if any. */
    fun cachedMessages(sessionId: String): List<ChatMessage>?

    /** Cache first (traces are immutable); otherwise GET /api/messages/{id}/debug. */
    suspend fun trace(messageId: String): Result<DebugTrace>
}

interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}
