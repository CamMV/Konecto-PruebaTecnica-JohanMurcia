package com.konecto.series76.sync

import androidx.room.withTransaction
import com.konecto.series76.core.AppError
import com.konecto.series76.core.IoDispatcher
import com.konecto.series76.data.local.db.KonectoDatabase
import com.konecto.series76.data.local.db.MessageEntity
import com.konecto.series76.data.local.prefs.AppPreferences
import com.konecto.series76.data.mapper.Mappers
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.data.remote.dto.ConversationRequest
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends what was written offline: pending deletions and pending messages (oldest first, one at a time, because the
 * multi-turn context depends on the order). A [Mutex] makes concurrent triggers (UI, reconnection, WorkManager)
 * safe, and `client_message_id` makes retries idempotent on the backend.
 */
@Singleton
class OutboxProcessor @Inject constructor(
    private val api: KonectoApi,
    private val db: KonectoDatabase,
    private val mappers: Mappers,
    private val errors: ApiErrorMapper,
    private val prefs: AppPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    enum class Outcome { DONE, RETRY_LATER }

    private val mutex = Mutex()
    private val sessions = db.sessionDao()
    private val messages = db.messageDao()

    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the messages being sent right now (the chat shows "typing…"). */
    val inFlight: StateFlow<Set<String>> = _inFlight

    suspend fun flush(): Outcome = mutex.withLock { withContext(io) { flushLocked() } }

    private suspend fun flushLocked(): Outcome {
        val userId = prefs.await().session?.user?.id ?: return Outcome.DONE
        if (syncDeletions(userId) == Outcome.RETRY_LATER) return Outcome.RETRY_LATER
        while (true) {
            val next = messages.pendingForUser(userId).firstOrNull() ?: return Outcome.DONE
            when (send(next)) {
                SendResult.SENT, SendResult.FAILED -> continue
                SendResult.NO_NETWORK -> return Outcome.RETRY_LATER
                SendResult.LOGGED_OUT -> return Outcome.DONE
            }
        }
    }

    private suspend fun syncDeletions(userId: String): Outcome {
        for (session in sessions.pendingDeletes(userId)) {
            try {
                val response = errors.call { api.deleteSession(session.id) }
                when {
                    response.isSuccessful || response.code() == 404 -> sessions.delete(session.id) // 404: never synced
                    response.code() == 401 -> return Outcome.DONE
                    response.code() >= 500 -> return Outcome.RETRY_LATER
                    else -> sessions.delete(session.id)
                }
            } catch (_: AppError.Network) {
                return Outcome.RETRY_LATER
            }
        }
        return Outcome.DONE
    }

    private enum class SendResult { SENT, FAILED, NO_NETWORK, LOGGED_OUT }

    private suspend fun send(message: MessageEntity): SendResult {
        _inFlight.update { it + message.id }
        try {
            val response = errors.call {
                api.converse(ConversationRequest(message.sessionId, message.content, message.clientMessageId))
            }
            val answer = mappers.assistantEntity(response)
            db.withTransaction {
                val session = sessions.get(message.sessionId) ?: return@withTransaction // deleted meanwhile
                // Keep the question before its answer even if the device clock is ahead of the server.
                messages.markSent(message.id, minOf(message.createdAt, answer.createdAt - 1))
                messages.insert(answer)
                sessions.upsert(
                    session.copy(
                        syncState = if (session.syncState == SyncState.PENDING_DELETE) session.syncState else SyncState.SYNCED,
                        updatedAt = maxOf(session.updatedAt, answer.createdAt),
                        lastPreview = response.answer.take(PREVIEW_MAX),
                        messageCount = session.messageCount + 1,
                    ),
                )
            }
            return SendResult.SENT
        } catch (_: AppError.Network) {
            return SendResult.NO_NETWORK // stays PENDING; retried on reconnection / by WorkManager
        } catch (_: AppError.Unauthorized) {
            return SendResult.LOGGED_OUT
        } catch (e: AppError) {
            // 5xx (LLM down/timeout) or 4xx: the user decides when to retry (same client_message_id → idempotent).
            messages.setSendState(message.id, SendState.FAILED, e.message)
            return SendResult.FAILED
        } finally {
            _inFlight.update { it - message.id }
        }
    }

    private companion object {
        const val PREVIEW_MAX = 120
    }
}
