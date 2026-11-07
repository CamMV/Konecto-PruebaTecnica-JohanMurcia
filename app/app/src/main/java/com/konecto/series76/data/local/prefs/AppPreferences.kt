package com.konecto.series76.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.konecto.series76.BuildConfig
import com.konecto.series76.core.ApplicationScope
import com.konecto.series76.domain.model.AuthSession
import com.konecto.series76.domain.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent app state in DataStore: backend URL + auth session. Exposed as a [StateFlow] so the rest of the app
 * (and the OkHttp interceptors) read it without touching disk on every request.
 */
@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) {
    data class Snapshot(val baseUrl: String, val session: AuthSession?)

    /** null until the first read from disk finishes. */
    val snapshot: StateFlow<Snapshot?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSnapshot() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun await(): Snapshot = snapshot.filterNotNull().first()

    /** Only for OkHttp threads (interceptors), never the main thread. Returns immediately once loaded. */
    fun current(): Snapshot = snapshot.value ?: runBlocking { await() }

    suspend fun saveSession(session: AuthSession) {
        dataStore.edit {
            it[TOKEN] = session.token
            it[EXPIRES_AT] = session.expiresAtMillis
            it[USER_ID] = session.user.id
            it[USER_EMAIL] = session.user.email
            it[USER_NAME] = session.user.name
            it[USER_IS_ADMIN] = session.user.isAdmin
        }
    }

    /** Refreshes the stored profile (e.g. the role changed on the server) if it is still the same user. */
    suspend fun updateUser(user: User) {
        dataStore.edit {
            if (it[USER_ID] != user.id) return@edit
            it[USER_EMAIL] = user.email
            it[USER_NAME] = user.name
            it[USER_IS_ADMIN] = user.isAdmin
        }
    }

    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN)
            prefs.remove(EXPIRES_AT)
            prefs.remove(USER_ID)
            prefs.remove(USER_EMAIL)
            prefs.remove(USER_NAME)
            prefs.remove(USER_IS_ADMIN)
        }
    }

    suspend fun saveBaseUrl(url: String) {
        dataStore.edit { it[BASE_URL] = url }
    }

    private fun Preferences.toSnapshot(): Snapshot {
        val token = this[TOKEN]
        val userId = this[USER_ID]
        val session = if (token != null && userId != null) {
            AuthSession(
                token = token,
                expiresAtMillis = this[EXPIRES_AT] ?: 0L,
                user = User(userId, this[USER_EMAIL].orEmpty(), this[USER_NAME].orEmpty(), this[USER_IS_ADMIN] ?: false),
            )
        } else null
        return Snapshot(baseUrl = this[BASE_URL] ?: BuildConfig.DEFAULT_BASE_URL, session = session)
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
        val EXPIRES_AT = longPreferencesKey("token_expires_at")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_IS_ADMIN = booleanPreferencesKey("user_is_admin")
    }
}
