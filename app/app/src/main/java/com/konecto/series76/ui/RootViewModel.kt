package com.konecto.series76.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konecto.series76.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decides the start screen (the system splash stays until it is known) and reports forced logouts. */
@HiltViewModel
class RootViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    enum class Start { LOADING, LOGGED_IN, LOGGED_OUT }

    private val _start = MutableStateFlow(Start.LOADING)
    val start: StateFlow<Start> = _start

    val forcedLogout: SharedFlow<Unit> = auth.forcedLogout

    init {
        viewModelScope.launch {
            // A saved session opens the chats right away, even offline (cache); the token is checked in background.
            _start.value = if (auth.awaitLoaded() != null) Start.LOGGED_IN else Start.LOGGED_OUT
            auth.validateToken()
        }
    }
}
