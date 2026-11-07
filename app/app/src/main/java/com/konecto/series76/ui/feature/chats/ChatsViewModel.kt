package com.konecto.series76.ui.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konecto.series76.core.DateFormats
import com.konecto.series76.core.DefaultDispatcher
import com.konecto.series76.domain.model.ChatSession
import com.konecto.series76.domain.model.SyncState
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.ChatRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionItem(val id: String, val title: String, val subtitle: String, val pendingSync: Boolean)

data class ChatsUiState(
    val sessions: List<SessionItem> = emptyList(),
    val loaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val pendingCount: Int = 0,
    val error: String? = null,
    val deleteCandidate: SessionItem? = null,
    /** Only administrators see the Debug button. */
    val isAdmin: Boolean = false,
)

/** Chat list: cached sessions shown at once (Room), refreshed from the API on open / pull / reconnection. */
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val chats: ChatRepository,
    private val auth: AuthRepository,
    network: NetworkMonitor,
    @DefaultDispatcher default: CoroutineDispatcher,
) : ViewModel() {

    /** [removed]: chats confirmed for deletion, hidden right away (before Room drops them) so the swiped card never bounces back. */
    private data class Local(
        val refreshing: Boolean = false,
        val error: String? = null,
        val delete: SessionItem? = null,
        val removed: Set<String> = emptySet(),
    )

    private val local = MutableStateFlow(Local())

    private val items = chats.observeSessions()
        .map { list -> list.map { it.toItem() } }
        .flowOn(default)

    val uiState: StateFlow<ChatsUiState> = combine(
        items, local, network.isOnline, chats.observePendingCount(), auth.session,
    ) { s, l, online, pending, session ->
        ChatsUiState(
            sessions = if (l.removed.isEmpty()) s else s.filterNot { it.id in l.removed }, loaded = true, isRefreshing = l.refreshing, isOnline = online, pendingCount = pending,
            error = l.error, deleteCandidate = l.delete, isAdmin = session?.user?.isAdmin == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    private val _loggedOut = Channel<Unit>(Channel.BUFFERED)
    val loggedOut = _loggedOut.receiveAsFlow()

    init {
        refresh()
        // Connectivity listener: refresh when the network comes back.
        viewModelScope.launch { network.isOnline.drop(1).filter { it }.collect { refresh() } }
    }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = chats.refreshSessions()
            local.update { it.copy(refreshing = false, error = result.exceptionOrNull()?.message) }
        }
    }

    fun requestDelete(item: SessionItem) = local.update { it.copy(delete = item) }
    fun cancelDelete() = local.update { it.copy(delete = null) }

    fun confirmDelete() {
        val item = local.value.delete ?: return
        local.update { it.copy(delete = null, removed = it.removed + item.id) }
        viewModelScope.launch { chats.deleteSession(item.id) }
    }

    fun logout() {
        viewModelScope.launch {
            auth.logout()
            _loggedOut.send(Unit)
        }
    }

    private fun ChatSession.toItem() = SessionItem(
        id = id,
        title = title,
        subtitle = DateFormats.card(updatedAt) + if (syncState == SyncState.PENDING_CREATE) " · not synced" else "",
        pendingSync = syncState == SyncState.PENDING_CREATE,
    )
}
