package com.konecto.series76.data.remote

import com.konecto.series76.core.AppError
import com.konecto.series76.data.remote.dto.ErrorEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Maps Retrofit/OkHttp/serialization failures to [AppError], reading the backend's `{"error": {...}}` body. */
@Singleton
class ApiErrorMapper @Inject constructor(private val json: Json) {

    fun map(t: Throwable): AppError = when (t) {
        is AppError -> t
        is HttpException -> fromHttp(t.code(), runCatching { t.response()?.errorBody()?.string() }.getOrNull())
        is IOException -> AppError.Network(t)
        else -> AppError.Unknown(t) // includes SerializationException (unexpected body)
    }

    fun fromHttp(status: Int, body: String?): AppError {
        val err = body?.let { runCatching { json.decodeFromString<ErrorEnvelope>(it).error }.getOrNull() }
        val message = err?.message ?: "Server error ($status)"
        return if (status == 401) AppError.Unauthorized(message)
        else AppError.Api(status, err?.code ?: "HTTP_$status", message)
    }

    /** Runs an API call and rethrows any failure as [AppError] (cancellation is propagated untouched). */
    suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        throw map(t)
    }
}
