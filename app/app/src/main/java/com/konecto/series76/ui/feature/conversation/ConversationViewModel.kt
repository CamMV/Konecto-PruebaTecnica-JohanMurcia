package com.konecto.series76.ui.feature.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.konecto.series76.core.DateFormats
import com.konecto.series76.core.DefaultDispatcher
import com.konecto.series76.domain.model.AnswerStatus
import com.konecto.series76.domain.model.ChatMessage
import com.konecto.series76.domain.model.ProductCard
import com.konecto.series76.domain.model.Role
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.repository.ChatRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import com.konecto.series76.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MessageItem(
    val id: String,
    val isUser: Boolean,
    val content: String,
    val status: AnswerStatus?,
    val products: List<ProductCard>,
    val suggestions: List<String>,
    val time: String,
    val sendState: SendState,
    val error: String?,
    val sending: Boolean,
)

data class ConversationUiState(
    val title: String = "New Chat",
    val messages: List<MessageItem> = emptyList(),
    val isOnline: Boolean = true,
    /** A question is waiting for its answer (shows "typing…" when online). */
    val awaitingAnswer: Boolean = false,
    val refreshError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chats: ChatRepository,
    network: NetworkMonitor,
    @DefaultDispatcher default: CoroutineDispatcher,
) : ViewModel() {

    /** null until the first message creates the conversation (its id is generated locally). */
    private val sessionId = MutableStateFlow(savedStateHandle.toRoute<Route.Conversation>().sessionId)

    /** Text being typed (synchronous Compose state, see LoginViewModel). */
    var input by mutableStateOf("")
        private set

    private data class Local(val refreshing: Boolean = false, val error: String? = null)

    private val local = MutableStateFlow(Local())

    private val session = sessionId.flatMapLatest { id -> if (id == null) flowOf(null) else chats.observeSession(id) }

    private val messages = combine(
        sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.observeMessages(id) },
        chats.sendingMessageIds,
    ) { list, sending -> list.map { it.toItem(it.id in sending) } }.flowOn(default)

    val uiState: StateFlow<ConversationUiState> = combine(session, messages, local, network.isOnline) { s, m, l, online ->
        ConversationUiState(
            title = s?.title ?: "New Chat",
            messages = m,
            isOnline = online,
            awaitingAnswer = m.any { it.isUser && it.sendState == SendState.PENDING },
            refreshError = l.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    init {
        refresh()
        viewModelScope.launch { network.isOnline.drop(1).filter { it }.collect { refresh() } }
    }

    fun refresh() {
        val id = sessionId.value ?: return
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = chats.refreshMessages(id)
            local.update { it.copy(refreshing = false, error = result.exceptionOrNull()?.message) }
        }
    }

    fun onInputChange(value: String) {
        input = value
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        send(text)
    }

    /** Suggestion card / chip: [prefillOnly] puts the text in the input so the user can finish it. */
    fun useSuggestion(text: String, prefillOnly: Boolean = false) {
        if (prefillOnly) input = text else send(text)
    }

    fun retry(messageId: String) {
        viewModelScope.launch { chats.retryMessage(messageId) }
    }

    private fun send(text: String) {
        viewModelScope.launch {
            val id = chats.sendMessage(sessionId.value, text)
            if (sessionId.value == null) {
                sessionId.value = id
                savedStateHandle["sessionId"] = id // survives process death / recreation
            }
        }
    }

    private fun ChatMessage.toItem(isSending: Boolean) = MessageItem(
        id = id,
        isUser = role == Role.USER,
        content = content,
        status = status,
        products = products,
        suggestions = suggestions,
        time = DateFormats.time(createdAt),
        sendState = sendState,
        error = errorMessage,
        sending = isSending,
    )
}
