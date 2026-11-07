package com.konecto.series76.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SessionDao {

    @Query("SELECT * FROM sessions WHERE user_id = :userId AND sync_state != 'PENDING_DELETE' ORDER BY updated_at DESC")
    abstract fun observeVisible(userId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE user_id = :userId")
    abstract suspend fun allForUser(userId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE user_id = :userId AND sync_state = 'PENDING_DELETE'")
    abstract suspend fun pendingDeletes(userId: String): List<SessionEntity>

    @Upsert
    abstract suspend fun upsert(session: SessionEntity)

    @Upsert
    abstract suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("UPDATE sessions SET sync_state = :state WHERE id = :id")
    abstract suspend fun setSyncState(id: String, state: SyncState)

    @Query("DELETE FROM sessions WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM sessions WHERE id IN (:ids)")
    abstract suspend fun deleteAll(ids: List<String>)

    /**
     * Cache-then-network merge of GET /api/sessions: remote rows win, except local pending changes
     * (conversations created offline and deletions not yet synced are kept as they are).
     */
    @Transaction
    open suspend fun mergeRemote(userId: String, remote: List<SessionEntity>) {
        val local = allForUser(userId).associateBy { it.id }
        val remoteIds = remote.map { it.id }.toSet()
        val gone = local.values.filter { it.syncState == SyncState.SYNCED && it.id !in remoteIds }.map { it.id }
        if (gone.isNotEmpty()) deleteAll(gone)
        upsertAll(remote.filter { local[it.id]?.syncState != SyncState.PENDING_DELETE })
    }
}

@Dao
abstract class MessageDao {

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    abstract fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    /** Outbox: pending user messages of the user, oldest first (multi-turn order matters). */
    @Query(
        """
        SELECT m.* FROM messages m JOIN sessions s ON s.id = m.session_id
        WHERE s.user_id = :userId AND m.send_state = 'PENDING' AND s.sync_state != 'PENDING_DELETE'
        ORDER BY m.created_at ASC, m.rowid ASC
        """,
    )
    abstract suspend fun pendingForUser(userId: String): List<MessageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM messages m JOIN sessions s ON s.id = m.session_id
        WHERE s.user_id = :userId AND m.send_state = 'PENDING'
        """,
    )
    abstract fun observePendingCount(userId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET send_state = :state, error_message = :error WHERE id = :id")
    abstract suspend fun setSendState(id: String, state: SendState, error: String?)

    @Query("UPDATE messages SET send_state = 'SENT', error_message = NULL, created_at = :createdAt WHERE id = :id")
    abstract suspend fun markSent(id: String, createdAt: Long)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND send_state = 'SENT'")
    abstract suspend fun deleteSent(sessionId: String)

    /** Replaces the synced history of a conversation with the server's, keeping PENDING/FAILED messages. */
    @Transaction
    open suspend fun replaceSent(sessionId: String, remote: List<MessageEntity>) {
        deleteSent(sessionId)
        insertAll(remote)
    }
}

@Dao
interface DebugTraceDao {
    @Query("SELECT * FROM debug_traces WHERE message_id = :messageId")
    suspend fun get(messageId: String): DebugTraceEntity?

    @Upsert
    suspend fun upsert(trace: DebugTraceEntity)
}
