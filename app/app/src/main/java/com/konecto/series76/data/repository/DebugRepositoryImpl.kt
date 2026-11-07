package com.konecto.series76.data.repository

import com.konecto.series76.core.DefaultDispatcher
import com.konecto.series76.core.resultOf
import com.konecto.series76.data.local.db.DebugTraceEntity
import com.konecto.series76.data.local.db.KonectoDatabase
import com.konecto.series76.data.mapper.Mappers
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.domain.model.DebugTrace
import com.konecto.series76.domain.repository.DebugRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugRepositoryImpl @Inject constructor(
    private val api: KonectoApi,
    db: KonectoDatabase,
    private val mappers: Mappers,
    private val errors: ApiErrorMapper,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : DebugRepository {

    private val dao = db.debugTraceDao()

    override suspend fun trace(messageId: String): Result<DebugTrace> = resultOf {
        val cached = dao.get(messageId)?.let { e ->
            withContext(default) { runCatching { mappers.decodeTrace(e.json) }.getOrNull() }
        }
        val dto = cached ?: errors.call { api.debugTrace(messageId) }.also { fresh ->
            val raw = withContext(default) { mappers.encodeTrace(fresh) }
            dao.upsert(DebugTraceEntity(messageId, raw, System.currentTimeMillis()))
        }
        withContext(default) { mappers.trace(dto) }
    }
}
