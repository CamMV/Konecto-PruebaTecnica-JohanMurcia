package com.konecto.series76.ui.feature.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.konecto.series76.core.AppError
import com.konecto.series76.core.DateFormats
import com.konecto.series76.domain.model.ChatMessage
import com.konecto.series76.domain.model.DebugSession
import com.konecto.series76.domain.model.DebugTrace
import com.konecto.series76.domain.model.Role
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.User
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.ChatRepository
import com.konecto.series76.domain.repository.DebugRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import com.konecto.series76.domain.repository.ServerConfigRepository
import com.konecto.series76.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun debugErrorMessage(e: Throwable): String = when {
    e is AppError.Api && e.code == "DEBUG_DISABLED" -> "The debug API is disabled on the server (DEBUG_API_ENABLED=false)."
    e is AppError.Network -> "Can't reach the server. Pull to retry when you're back online."
    else -> e.message ?: "Error"
}

// ---------------------------------------------------------------- Debug · chats

data class DebugListItem(val id: String, val title: String, val subtitle: String, val label: String)

/** The chats of one user, under a header with their name. */
data class DebugUserGroup(val user: User, val isMe: Boolean, val chats: List<DebugListItem>)

data class DebugChatsUiState(
    val groups: List<DebugUserGroup> = emptyList(),
    val loaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val serverUrl: String = "",
    val error: String? = null,
)

/**
 * Groups every user's chats by owner: the logged-in user first, then the rest by name. Inside a group the chats keep
 * the server order (newest first) and are numbered per user ("Chat 1" = newest).
 */
internal fun groupByUser(sessions: List<DebugSession>, meId: String?): List<DebugUserGroup> =
    sessions.groupBy { it.owner.id }.values
        .map { chats ->
            val owner = chats.first().owner
            DebugUserGroup(
                user = owner,
                isMe = owner.id == meId,
                chats = chats.mapIndexed { i, s ->
                    DebugListItem(s.id, s.title, DateFormats.card(s.updatedAt), "Chat ${i + 1}")
                },
            )
        }
        .sortedWith(compareByDescending<DebugUserGroup> { it.isMe }.thenBy { it.user.name.lowercase() })

private data class ChatsLoad(
    val sessions: List<DebugSession> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/** Debug · chats (Figma 31:1139): every user's chats from the server (admin endpoint), grouped by user. */
@HiltViewModel
class DebugChatsViewModel @Inject constructor(
    private val debug: DebugRepository,
    auth: AuthRepository,
    network: NetworkMonitor,
    server: ServerConfigRepository,
) : ViewModel() {
    private val load = MutableStateFlow(ChatsLoad())

    val uiState: StateFlow<DebugChatsUiState> =
        combine(load, auth.session, network.isOnline, server.baseUrl) { l, session, online, url ->
            DebugChatsUiState(
                groups = groupByUser(l.sessions, session?.user?.id),
                loaded = l.loaded,
                isRefreshing = l.refreshing,
                isOnline = online,
                serverUrl = url,
                error = l.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugChatsUiState())

    init { refresh() }

    fun refresh() {
        if (load.value.refreshing) return
        load.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            debug.sessions()
                .onSuccess { list -> load.value = ChatsLoad(list, loaded = true) }
                // Keep the last list on screen if a refresh fails.
                .onFailure { e -> load.update { it.copy(loaded = true, refreshing = false, error = debugErrorMessage(e)) } }
        }
    }
}

// ---------------------------------------------------------------- Debug · answers of a chat

/** One answer in the debug list: the question that triggered it + a plain-text preview of the answer. */
data class DebugAnswerItem(
    val id: String,
    val question: String?,
    val answerPreview: String,
    val meta: String,
    val label: String,
)

data class DebugAnswersUiState(
    val items: List<DebugAnswerItem> = emptyList(),
    val loaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val error: String? = null,
)

/** Pairs each sent answer with the user message before it. */
internal fun answerItems(messages: List<ChatMessage>): List<DebugAnswerItem> {
    var lastQuestion: String? = null
    var n = 0
    return buildList {
        for (m in messages) {
            if (m.role == Role.USER) {
                lastQuestion = m.content
            } else if (m.sendState == SendState.SENT) {
                n++
                add(
                    DebugAnswerItem(
                        id = m.id,
                        question = lastQuestion,
                        answerPreview = plainText(m.content),
                        meta = DateFormats.card(m.createdAt) + (m.status?.let { " · ${it.label}" } ?: ""),
                        label = "Ans $n",
                    ),
                )
            }
        }
    }
}

/** Debug · answers of one chat (Figma 39:1453). The chat may belong to any user. */
@HiltViewModel
class DebugAnswersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val debug: DebugRepository,
    private val chats: ChatRepository,
    network: NetworkMonitor,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Route.DebugAnswers>()
    val chatLabel: String = route.chatLabel
    val userName: String = route.userName
    private val state = MutableStateFlow(DebugAnswersUiState(isRefreshing = true))

    val uiState: StateFlow<DebugAnswersUiState> = combine(state, network.isOnline) { s, online -> s.copy(isOnline = online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugAnswersUiState(isRefreshing = true))

    init { refresh() }

    fun refresh() {
        state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            debug.messages(route.sessionId)
                .onSuccess { list -> state.value = DebugAnswersUiState(answerItems(list), loaded = true) }
                .onFailure { e ->
                    // Offline: the logged-in user's own chats are still in the local cache.
                    val cached = chats.observeMessages(route.sessionId).first()
                    state.value = DebugAnswersUiState(answerItems(cached), loaded = true, error = debugErrorMessage(e))
                }
        }
    }
}

// ---------------------------------------------------------------- Debug · one answer

data class DebugAnswerUiState(
    val title: String = "",
    val userName: String? = null,
    val question: String? = null,
    val answer: String? = null,
    val loading: Boolean = true,
    val trace: DebugTrace? = null,
    val error: String? = null,
)

/** Debug · one answer (Figma 39:1507): question, answer, retrieved data, tools called, flow. */
@HiltViewModel
class DebugAnswerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chats: ChatRepository,
    private val debug: DebugRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Route.DebugAnswer>()
    private val _uiState = MutableStateFlow(DebugAnswerUiState(title = route.title, userName = route.userName))
    val uiState: StateFlow<DebugAnswerUiState> = _uiState

    init { load() }

    fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // History loaded by the answers screen (any user), or the local cache (own chats).
            val messages = debug.cachedMessages(route.sessionId) ?: chats.observeMessages(route.sessionId).first()
            val index = messages.indexOfFirst { it.id == route.messageId }
            val question = messages.take(index.coerceAtLeast(0)).lastOrNull { it.role == Role.USER }?.content
            val answer = messages.getOrNull(index)?.content
            _uiState.update { it.copy(question = question, answer = answer) }
            debug.trace(route.messageId)
                .onSuccess { t -> _uiState.update { it.copy(loading = false, trace = t) } }
                .onFailure { e ->
                    val msg = if (e is AppError.Network) {
                        "This trace isn't saved on the phone yet. Connect to the server to load it."
                    } else {
                        debugErrorMessage(e)
                    }
                    _uiState.update { it.copy(loading = false, error = msg) }
                }
        }
    }
}

private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val MD_LINE_PREFIX = Regex("""(?m)^\s*(#{1,6}\s+|[-*+]\s+|>\s*)""")
private val MD_EMPHASIS = Regex("""\*\*|__|`|~~""")
private val WHITESPACE = Regex("""\s+""")

/** Markdown → one line of plain text, for list previews. */
internal fun plainText(markdown: String): String = markdown
    .replace(MD_LINK, "$1")
    .replace(MD_LINE_PREFIX, "")
    .replace(MD_EMPHASIS, "")
    .replace(WHITESPACE, " ")
    .trim()
