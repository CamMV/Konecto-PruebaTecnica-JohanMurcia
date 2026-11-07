package com.konecto.series76.data.remote.interceptor

import com.konecto.series76.data.local.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites scheme/host/port (and path prefix) of every request with the backend URL saved in [AppPreferences].
 * Retrofit is built once with a placeholder base URL; changing the backend URL is just a state change.
 */
@Singleton
class BaseUrlInterceptor @Inject constructor(private val prefs: AppPreferences) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(ABSOLUTE_URL_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(ABSOLUTE_URL_HEADER).build())
        }
        return chain.proceed(request.newBuilder().url(rewrite(parsedBase(prefs.current().baseUrl), request.url)).build())
    }

    /** The base URL changes rarely: parse it once per value instead of on every request. */
    @Volatile private var cachedBase: Pair<String, HttpUrl>? = null

    private fun parsedBase(raw: String): HttpUrl {
        cachedBase?.let { (key, url) -> if (key == raw) return url }
        return raw.toHttpUrl().also { cachedBase = raw to it }
    }

    companion object {
        /** Requests with this header already carry their full URL (e.g. testing a server before saving it). */
        const val ABSOLUTE_URL_HEADER = "X-Konecto-Absolute-Url"
        const val PLACEHOLDER_BASE_URL = "http://localhost/"

        /** `http://localhost/api/x?q=1` + base `https://h:9/prefix/` → `https://h:9/prefix/api/x?q=1`. */
        fun rewrite(baseUrl: String, requestUrl: String): HttpUrl = rewrite(baseUrl.toHttpUrl(), requestUrl.toHttpUrl())

        fun rewrite(base: HttpUrl, original: HttpUrl): HttpUrl {
            val relative = original.encodedPath.removePrefix("/")
            val basePath = base.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
            return base.newBuilder()
                .encodedPath(basePath + relative)
                .encodedQuery(original.encodedQuery)
                .build()
        }
    }
}

/** Adds `Authorization: Bearer <token>` when there is a session. */
@Singleton
class AuthInterceptor @Inject constructor(private val prefs: AppPreferences) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = prefs.current().session?.token
        if (token == null || request.header("Authorization") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/** Bus of "the server rejected our token" events. Login (no token) failures are not reported. */
@Singleton
class AuthEvents @Inject constructor() {
    private val _unauthorized = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the rejected token, so a late 401 from an old token doesn't log out a fresh session. */
    val unauthorized: SharedFlow<String> = _unauthorized

    fun reportUnauthorized(token: String) {
        _unauthorized.tryEmit(token)
    }
}

@Singleton
class UnauthorizedInterceptor @Inject constructor(private val events: AuthEvents) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val auth = request.header("Authorization")
        if (response.code == 401 && auth != null) events.reportUnauthorized(auth.removePrefix("Bearer "))
        return response
    }
}
