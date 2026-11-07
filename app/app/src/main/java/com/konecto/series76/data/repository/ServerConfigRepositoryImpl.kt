package com.konecto.series76.data.repository

import com.konecto.series76.BuildConfig
import com.konecto.series76.core.AppError
import com.konecto.series76.core.ApplicationScope
import com.konecto.series76.core.resultOf
import com.konecto.series76.data.local.prefs.AppPreferences
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.domain.model.ServerHealth
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.ServerConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerConfigRepositoryImpl @Inject constructor(
    private val api: KonectoApi,
    private val prefs: AppPreferences,
    private val auth: AuthRepository,
    private val errors: ApiErrorMapper,
    @ApplicationScope scope: CoroutineScope,
) : ServerConfigRepository {

    override val baseUrl: StateFlow<String> = prefs.snapshot
        .map { it?.baseUrl ?: BuildConfig.DEFAULT_BASE_URL }
        .stateIn(scope, SharingStarted.Eagerly, BuildConfig.DEFAULT_BASE_URL)

    override fun normalize(input: String): String? = normalizeBaseUrl(input)

    override suspend fun testConnection(url: String): Result<ServerHealth> = resultOf {
        val normalized = normalize(url) ?: throw invalidUrl()
        val health = errors.call { api.healthAt(normalized + "api/health") }
        ServerHealth(ok = health.status == "ok", dbUp = health.db, actuators = health.actuators)
    }

    override suspend fun setBaseUrl(url: String): Result<Unit> = resultOf {
        val normalized = normalize(url) ?: throw invalidUrl()
        if (normalized != prefs.await().baseUrl) {
            // The cached chats and the token belong to the previous server.
            auth.logout()
            prefs.saveBaseUrl(normalized)
        }
    }

    private fun invalidUrl() = AppError.Api(0, "INVALID_URL", "Invalid URL. Example: http://10.0.2.2:8001")

    companion object {
        private val TRAILING_NOISE = setOf("docs", "health", "api")

        /** "10.0.2.2:8001/api/health" → "http://10.0.2.2:8001/". Keeps a path prefix (reverse proxies). */
        fun normalizeBaseUrl(input: String): String? {
            var raw = input.trim()
            if (raw.isEmpty()) return null
            if (!raw.contains("://")) raw = "http://$raw"
            val url = raw.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            while (segments.isNotEmpty() && segments.last().lowercase() in TRAILING_NOISE) segments.removeAt(segments.lastIndex)
            val path = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/") + "/"
            return url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
        }
    }
}
