package com.konecto.series76.data.repository

import androidx.room.withTransaction
import com.konecto.series76.core.AppError
import com.konecto.series76.core.DefaultDispatcher
import com.konecto.series76.core.resultOf
import com.konecto.series76.data.local.db.KonectoDatabase
import com.konecto.series76.data.local.db.SessionEntity
import com.konecto.series76.data.mapper.Mappers
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.domain.model.ChatMessage
import com.konecto.series76.domain.model.ChatSession
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.ChatRepository
import com.konecto.series76.sync.OutboxProcessor
import com.konecto.series76.sync.SyncScheduler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache-then-network chats. Room is the single source of truth: screens observe it (instant, offline) while
 * refresh* calls update it from the API. Sends and deletions are written locally first and the outbox syncs them.
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val api: KonectoApi,
    private val db: KonectoDatabase,
    private val mappers: Mappers,
    private val errors: ApiErrorMapper,
    private val auth: AuthRepository,
    private val sync: SyncScheduler,
    outbox: OutboxProcessor,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ChatRepository {

    private val sessions = db.sessionDao()
    private val messages = db.messageDao()

    private fun userId(): String = auth.session.value?.user?.id ?: throw AppError.Unauthorized()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSessions(): Flow<List<ChatSession>> = auth.session
        .map { it?.user?.id }
        .distinctUntilChanged()
        .flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else sessions.observeVisible(uid) }
        .map { list -> list.map(mappers::session) }
        .flowOn(default)

    override fun observeSession(sessionId: String): Flow<ChatSession?> = sessions.observe(sessionId)
        .map { it?.let(mappers::session) }
        .flowOn(default)

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = messages.observeForSession(sessionId)
        .map { list -> list.map(mappers::message) }
        .flowOn(default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingCount(): Flow<Int> = auth.session
        .map { it?.user?.id }
        .distinctUntilChanged()
        .flatMapLatest { uid -> if (uid == null) flowOf(0) else messages.observePendingCount(uid) }

    override val sendingMessageIds: StateFlow<Set<String>> = outbox.inFlight

    override suspend fun refreshSessions(): Result<Unit> = resultOf {
        val uid = userId()
        val dto = errors.call { api.sessions(limit = 100) }
        val entities = withContext(default) { dto.items.map { mappers.sessionEntity(it, uid) } }
        sessions.mergeRemote(uid, entities)
    }

    override suspend fun refreshMessages(sessionId: String): Result<Unit> = resultOf {
        val local = sessions.get(sessionId)
        // Created offline (not on the server yet) or being deleted: nothing to download.
        if (local == null || local.syncState != SyncState.SYNCED) return@resultOf
        try {
            val dto = errors.call { api.messages(sessionId) }
            val entities = withContext(default) { dto.messages.map { mappers.messageEntity(it, sessionId) } }
            db.withTransaction {
                messages.replaceSent(sessionId, entities)
                sessions.get(sessionId)?.let { sessions.upsert(it.copy(title = dto.title)) }
            }
        } catch (e: AppError.Api) {
            if (e.code == "SESSION_NOT_FOUND") sessions.delete(sessionId) else throw e
        }
    }

    override suspend fun sendMessage(sessionId: String?, text: String): String {
        val uid = userId()
        val query = text.trim()
        val now = System.currentTimeMillis()
        // Unknown session ids are created by the backend with that id, so conversations can start offline.
        val sid = sessionId ?: UUID.randomUUID().toString()
        db.withTransaction {
            val existing = sessions.get(sid)
            sessions.upsert(
                existing?.copy(updatedAt = now, lastPreview = query, messageCount = existing.messageCount + 1)
                    ?: SessionEntity(
                        id = sid, userId = uid, title = query.take(TITLE_MAX), createdAt = now, updatedAt = now,
                        messageCount = 1, lastPreview = query, syncState = SyncState.PENDING_CREATE,
                    ),
            )
            messages.insert(mappers.pendingUserEntity(UUID.randomUUID().toString(), sid, query, now))
        }
        sync.requestSync()
        return sid
    }

    override suspend fun retryMessage(messageId: String) {
        messages.setSendState(messageId, SendState.PENDING, null)
        sync.requestSync()
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.setSyncState(sessionId, SyncState.PENDING_DELETE)
        sync.requestSync()
    }

    private companion object {
        const val TITLE_MAX = 60 // same rule as the backend (title = first query, 60 chars)
    }
}
