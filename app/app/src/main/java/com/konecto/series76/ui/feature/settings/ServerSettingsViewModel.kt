package com.konecto.series76.ui.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konecto.series76.domain.repository.ServerConfigRepository
import com.konecto.series76.ui.components.ConnectionTest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerSettingsUiState(
    val currentUrl: String = "",
    val visible: Boolean = false,
    val test: ConnectionTest = ConnectionTest.Idle,
    val saving: Boolean = false,
    val error: String? = null,
)

/** State of the backend-URL sheet (shared by Login and Debug). */
@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val config: ServerConfigRepository,
) : ViewModel() {

    /** URL being edited (synchronous Compose state for the text field). */
    var draft by mutableStateOf("")
        private set

    private val form = MutableStateFlow(ServerSettingsUiState())

    val uiState: StateFlow<ServerSettingsUiState> = combine(form, config.baseUrl) { f, url -> f.copy(currentUrl = url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerSettingsUiState(currentUrl = config.baseUrl.value))

    /** Emits `true` when a different server was saved (the user was logged out). */
    private val _saved = Channel<Boolean>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    fun open() {
        draft = config.baseUrl.value
        form.update { ServerSettingsUiState(visible = true) }
    }

    fun dismiss() = form.update { it.copy(visible = false) }

    fun onDraftChange(value: String) {
        draft = value
        form.update { it.copy(test = ConnectionTest.Idle, error = null) }
    }

    fun test() {
        val url = draft
        form.update { it.copy(test = ConnectionTest.Running, error = null) }
        viewModelScope.launch {
            val result = config.testConnection(url)
            form.update {
                it.copy(test = result.fold({ h -> ConnectionTest.Ok(h) }, { e -> ConnectionTest.Failed(e.message ?: "Error") }))
            }
        }
    }

    fun save() {
        val normalized = config.normalize(draft)
        if (normalized == null) {
            form.update { it.copy(error = "Invalid URL. Example: http://10.0.2.2:8001") }
            return
        }
        val changed = normalized != config.baseUrl.value
        form.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            config.setBaseUrl(normalized)
                .onSuccess {
                    form.update { it.copy(saving = false, visible = false) }
                    _saved.send(changed)
                }
                .onFailure { e -> form.update { it.copy(saving = false, error = e.message) } }
        }
    }
}
