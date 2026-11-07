package com.konecto.series76.data.repository

import com.konecto.series76.core.AppError
import com.konecto.series76.core.ApplicationScope
import com.konecto.series76.core.IoDispatcher
import com.konecto.series76.core.resultOf
import com.konecto.series76.data.local.db.KonectoDatabase
import com.konecto.series76.data.local.prefs.AppPreferences
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.data.remote.dto.LoginRequest
import com.konecto.series76.data.remote.dto.UserDto
import com.konecto.series76.data.remote.interceptor.AuthEvents
import com.konecto.series76.domain.model.AuthSession
import com.konecto.series76.domain.model.User
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: KonectoApi,
    private val prefs: AppPreferences,
    private val db: KonectoDatabase,
    private val errors: ApiErrorMapper,
    private val network: NetworkMonitor,
    events: AuthEvents,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {

    override val session: StateFlow<AuthSession?> = prefs.snapshot
        .map { it?.session }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _forcedLogout = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val forcedLogout: SharedFlow<Unit> = _forcedLogout

    init {
        // Any authenticated request answered with 401 logs out, but only if it used the current token.
        scope.launch {
            events.unauthorized.collect { rejected ->
                if (prefs.await().session?.token == rejected) {
                    logout()
                    _forcedLogout.emit(Unit)
                }
            }
        }
    }

    override suspend fun awaitLoaded(): AuthSession? = prefs.await().session

    override suspend fun login(email: String, password: String): Result<AuthSession> = resultOf {
        val response = errors.call { api.login(LoginRequest(email.trim(), password)) }
        val newSession = AuthSession(
            token = response.accessToken,
            expiresAtMillis = System.currentTimeMillis() + response.expiresIn * 1000,
            user = response.user.toDomain(),
        )
        val previousUser = prefs.await().session?.user?.id
        if (previousUser != null && previousUser != newSession.user.id) withContext(io) { db.clearAllTables() }
        prefs.saveSession(newSession)
        newSession
    }

    override suspend fun validateToken() {
        if (!network.isOnline.value || prefs.await().session == null) return
        try {
            // Also picks up a role change made on the server since the last login.
            prefs.updateUser(errors.call { api.me() }.toDomain())
        } catch (_: AppError) {
            // 401 is handled by UnauthorizedInterceptor → AuthEvents; other errors: keep working offline.
        }
    }

    override suspend fun logout() {
        prefs.clearSession()
        withContext(io) { db.clearAllTables() }
    }

    private fun UserDto.toDomain() = User(id, email, name, isAdmin)
}
