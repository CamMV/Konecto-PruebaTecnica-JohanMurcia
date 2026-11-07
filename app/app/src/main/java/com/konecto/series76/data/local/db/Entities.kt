package com.konecto.series76.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.konecto.series76.domain.model.Role
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState

@Entity(tableName = "sessions", indices = [Index("user_id", "updated_at")])
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "last_preview") val lastPreview: String?,
    @ColumnInfo(name = "sync_state") val syncState: SyncState,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id", "created_at"), Index("send_state")],
)
data class MessageEntity(
    /** Server `message_id` for synced messages; a local UUID (= client_message_id) for pending ones. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: Role,
    val content: String,
    /** AnswerStatus wire value (assistant messages only). */
    val status: String?,
    /** ProductCard list as JSON: immutable snapshot of what the server returned. */
    @ColumnInfo(name = "products_json") val productsJson: String,
    @ColumnInfo(name = "suggestions_json") val suggestionsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "client_message_id") val clientMessageId: String?,
    @ColumnInfo(name = "send_state") val sendState: SendState,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
)

/** Debug traces are immutable once the answer exists, so they are cached forever. */
@Entity(tableName = "debug_traces")
data class DebugTraceEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
