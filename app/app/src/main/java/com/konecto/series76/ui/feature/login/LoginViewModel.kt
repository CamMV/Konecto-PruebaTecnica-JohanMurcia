package com.konecto.series76.ui.feature.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konecto.series76.core.AppError
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import com.konecto.series76.domain.repository.ServerConfigRepository
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

data class LoginUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val isOnline: Boolean = true,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    network: NetworkMonitor,
    private val server: ServerConfigRepository,
) : ViewModel() {

    // Text fields use synchronous Compose state: routing keystrokes through a StateFlow (async) drops characters.
    var email by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    private val form = MutableStateFlow(LoginUiState())

    val uiState: StateFlow<LoginUiState> = combine(form, network.isOnline) { f, online -> f.copy(isOnline = online) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoginUiState())

    private val _loggedIn = Channel<Unit>(Channel.BUFFERED)
    val loggedIn = _loggedIn.receiveAsFlow()

    fun onEmailChange(value: String) {
        email = value
        form.update { it.copy(error = null) }
    }

    fun onPasswordChange(value: String) {
        password = value
        form.update { it.copy(error = null) }
    }

    fun submit() {
        val s = form.value
        if (s.loading) return
        if (email.isBlank() || password.isEmpty()) {
            form.update { it.copy(error = "Enter your username and password") }
            return
        }
        form.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            auth.login(email, password)
                .onSuccess {
                    password = ""
                    form.update { it.copy(loading = false) }
                    _loggedIn.send(Unit)
                }
                .onFailure { e ->
                    val message = when (e) {
                        is AppError.Network -> "Can't reach ${server.baseUrl.value}. Check your connection or the server (⚙)."
                        else -> e.message ?: "Login failed"
                    }
                    form.update { it.copy(loading = false, error = message) }
                }
        }
    }
}
