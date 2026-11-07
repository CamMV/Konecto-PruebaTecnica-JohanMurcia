package com.konecto.series76.ui.feature.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.konecto.series76.core.AppError
import com.konecto.series76.core.DateFormats
import com.konecto.series76.core.DefaultDispatcher
import com.konecto.series76.domain.model.DebugTrace
import com.konecto.series76.domain.model.Role
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState
import com.konecto.series76.domain.repository.ChatRepository
import com.konecto.series76.domain.repository.DebugRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import com.konecto.series76.domain.repository.ServerConfigRepository
import com.konecto.series76.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugListItem(val id: String, val title: String, val subtitle: String, val label: String)

/** Refresh state of a debug list (spinner + last error). */
private data class RefreshState(val refreshing: Boolean = false, val error: String? = null)

data class DebugListUiState(
    val items: List<DebugListItem> = emptyList(),
    val loaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val serverUrl: String = "",
    val error: String? = null,
)

/** Debug · chats (Figma 31:1139). Same cached list as the chat screen. */
@HiltViewModel
class DebugChatsViewModel @Inject constructor(
    private val chats: ChatRepository,
    network: NetworkMonitor,
    server: ServerConfigRepository,
    @DefaultDispatcher default: CoroutineDispatcher,
) : ViewModel() {
    private val local = MutableStateFlow(RefreshState())

    private val items = chats.observeSessions().map { list ->
        list.filter { it.syncState == SyncState.SYNCED }.mapIndexed { i, s ->
            DebugListItem(s.id, s.title, DateFormats.card(s.updatedAt), "Chat ${i + 1}")
        }
    }.flowOn(default)

    val uiState: StateFlow<DebugListUiState> = combine(items, local, network.isOnline, server.baseUrl) { list, l, online, url ->
        DebugListUiState(list, loaded = true, isRefreshing = l.refreshing, isOnline = online, serverUrl = url, error = l.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugListUiState())

    init { refresh() }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            local.value = RefreshState(error = chats.refreshSessions().exceptionOrNull()?.message)
        }
    }
}

/** Debug · answers of one chat (Figma 39:1453). */
@HiltViewModel
class DebugAnswersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chats: ChatRepository,
    network: NetworkMonitor,
    @DefaultDispatcher default: CoroutineDispatcher,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Route.DebugAnswers>()
    val chatLabel: String = route.chatLabel
    private val local = MutableStateFlow(RefreshState(refreshing = true))

    private val items = chats.observeMessages(route.sessionId).map { list ->
        list.filter { it.role == Role.ASSISTANT && it.sendState == SendState.SENT }.mapIndexed { i, m ->
            DebugListItem(
                id = m.id,
                title = "Answer ${i + 1}",
                subtitle = DateFormats.card(m.createdAt) + (m.status?.let { " · ${it.label}" } ?: ""),
                label = "Ans ${i + 1}",
            )
        }
    }.flowOn(default)

    val uiState: StateFlow<DebugListUiState> = combine(items, local, network.isOnline) { list, l, online ->
        DebugListUiState(list, loaded = true, isRefreshing = l.refreshing, isOnline = online, error = l.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugListUiState())

    init {
        viewModelScope.launch {
            local.value = RefreshState(error = chats.refreshMessages(route.sessionId).exceptionOrNull()?.message)
        }
    }
}

data class DebugAnswerUiState(
    val title: String = "",
    val question: String? = null,
    val loading: Boolean = true,
    val trace: DebugTrace? = null,
    val error: String? = null,
)

/** Debug · one answer (Figma 39:1507): question, retrieved data, tools called, flow. */
@HiltViewModel
class DebugAnswerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chats: ChatRepository,
    private val debug: DebugRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Route.DebugAnswer>()
    private val _uiState = MutableStateFlow(DebugAnswerUiState(title = route.title))
    val uiState: StateFlow<DebugAnswerUiState> = _uiState

    init { load() }

    fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val messages = chats.observeMessages(route.sessionId).first()
            val index = messages.indexOfFirst { it.id == route.messageId }
            val question = messages.take(index.coerceAtLeast(0)).lastOrNull { it.role == Role.USER }?.content
            _uiState.update { it.copy(question = question) } // from the cache, before the trace arrives
            debug.trace(route.messageId)
                .onSuccess { t -> _uiState.update { it.copy(loading = false, trace = t) } }
                .onFailure { e ->
                    val msg = when {
                        e is AppError.Api && e.code == "DEBUG_DISABLED" -> "The debug API is disabled on the server (DEBUG_API_ENABLED=false)."
                        e is AppError.Network -> "This trace isn't saved on the phone yet. Connect to the server to load it."
                        else -> e.message ?: "Error"
                    }
                    _uiState.update { it.copy(loading = false, error = msg) }
                }
        }
    }
}
