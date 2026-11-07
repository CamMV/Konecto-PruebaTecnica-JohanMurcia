package com.konecto.series76.data

import com.konecto.series76.core.AppError
import com.konecto.series76.core.DateFormats
import com.konecto.series76.data.mapper.Mappers
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.interceptor.BaseUrlInterceptor
import com.konecto.series76.data.repository.ServerConfigRepositoryImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

class PureLogicTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `base url rewrite keeps path, query and prefix`() {
        assertEquals(
            "http://10.0.2.2:8001/api/sessions?limit=100&offset=0",
            BaseUrlInterceptor.rewrite("http://10.0.2.2:8001/", "http://localhost/api/sessions?limit=100&offset=0").toString(),
        )
        assertEquals(
            "https://example.com:9443/konecto/api/health",
            BaseUrlInterceptor.rewrite("https://example.com:9443/konecto/", "http://localhost/api/health").toString(),
        )
    }

    @Test fun `server url normalization`() {
        val n = ServerConfigRepositoryImpl::normalizeBaseUrl
        assertEquals("http://10.0.2.2:8001/", n("10.0.2.2:8001"))
        assertEquals("http://10.0.2.2:8001/", n(" http://10.0.2.2:8001/api/health "))
        assertEquals("http://localhost:8001/", n("http://localhost:8001/docs"))
        assertEquals("https://host.com/prefix/", n("https://host.com/prefix?x=1"))
        assertNull(n(""))
        assertNull(n("ftp://host"))
        assertNull(n("http://"))
    }

    @Test fun `api errors use the backend envelope`() {
        val mapper = ApiErrorMapper(json)
        val notFound = mapper.fromHttp(404, """{"error":{"code":"SESSION_NOT_FOUND","message":"Session not found"}}""")
        assertTrue(notFound is AppError.Api)
        assertEquals("SESSION_NOT_FOUND", (notFound as AppError.Api).code)
        assertEquals("Session not found", notFound.message)
        val llm = mapper.fromHttp(502, """{"error":{"code":"LLM_UNAVAILABLE","message":"LLM down"}}""") as AppError.Api
        assertTrue(llm.isServerSide)
        val unauthorized = mapper.fromHttp(401, """{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid email or password"}}""")
        assertTrue(unauthorized is AppError.Unauthorized)
        assertEquals("Invalid email or password", unauthorized.message)
        assertTrue(mapper.fromHttp(500, "<html>") is AppError.Api)
        assertTrue(mapper.map(IOException("timeout")) is AppError.Network)
    }

    @Test fun `iso dates from the backend`() {
        val a = DateFormats.parseIso("2026-11-03T18:20:11Z")
        assertEquals(a, DateFormats.parseIso("2026-11-03T18:20:11+00:00"))
        assertEquals(a + 123, DateFormats.parseIso("2026-11-03T18:20:11.123Z"))
        assertEquals(a, DateFormats.parseIso("2026-11-03T18:20:11")) // naive = UTC
        assertEquals("03 November 2026 | 06:20 PM", DateFormats.card(a, ZoneOffset.UTC))
    }

    @Test fun `tool results are summarized for the debug screen`() {
        val m = Mappers(json)
        val r = json.decodeFromString<JsonElement>(
            """{"match_type":"exact","found":true,"results":[{"base_part_number":"761A00-11300000/A","onoff_torque_inlb":700}]}""",
        )
        assertEquals("exact · 1 row: 761A00-11300000/A", m.summarize(r))
        val many = json.decodeFromString<JsonElement>(
            """{"results":[${(1..8).joinToString(",") { """{"base_part_number":"P$it"}""" }}]}""",
        )
        assertEquals("8 rows: P1, P2, P3, P4, P5, P6 (+2 more)", m.summarize(many))
        assertEquals("No result", m.summarize(null))
    }
}
