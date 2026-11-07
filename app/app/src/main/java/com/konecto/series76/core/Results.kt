package com.konecto.series76.core

import kotlinx.coroutines.CancellationException

/** Like runCatching, but never swallows coroutine cancellation, and maps unknown failures to [AppError]. */
inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (e: AppError) {
    Result.failure(e)
} catch (t: Throwable) {
    Result.failure(AppError.Unknown(t))
}
