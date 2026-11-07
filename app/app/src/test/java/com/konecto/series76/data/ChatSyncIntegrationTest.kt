package com.konecto.series76.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.konecto.series76.data.local.db.KonectoDatabase
import com.konecto.series76.data.local.db.SessionEntity
import com.konecto.series76.data.local.prefs.AppPreferences
import com.konecto.series76.data.mapper.Mappers
import com.konecto.series76.data.remote.ApiErrorMapper
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.data.repository.ChatRepositoryImpl
import com.konecto.series76.domain.model.AuthSession
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.SyncState
import com.konecto.series76.domain.model.User
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.sync.OutboxProcessor
import com.konecto.series76.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Offline-first flow end to end in the data layer: Room (in memory) + real repository/outbox + a fake backend
 * (MockWebServer) that speaks the contract of docs/api_contract.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatSyncIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var server: MockWebServer
    private lateinit var db: KonectoDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: AppPreferences
    private lateinit var repo: ChatRepositoryImpl
    private lateinit var outbox: OutboxProcessor
    private lateinit var offlineOutbox: OutboxProcessor
    private val syncRequests = MutableStateFlow(0)

    private val user = User("u1", "ana@konecto.test", "Ana")

    private class FakeAuth(session: AuthSession) : AuthRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(session)
        override val forcedLogout = MutableSharedFlow<Unit>()
        override suspend fun awaitLoaded() = session.value
        override suspend fun login(email: String, password: String) = Result.success(session.value!!)
        override suspend fun validateToken() = Unit
        override suspend fun logout() = Unit
    }

    private fun api(baseUrl: String): KonectoApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(KonectoApi::class.java)

    @Before fun setUp() = runBlocking {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KonectoDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        prefs = AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        val session = AuthSession("token", Long.MAX_VALUE, user)
        prefs.saveSession(session)
        val mappers = Mappers(json)
        val errors = ApiErrorMapper(json)
        val api = api(server.url("/").toString())
        outbox = OutboxProcessor(api, db, mappers, errors, prefs, Dispatchers.IO)
        offlineOutbox = OutboxProcessor(api("http://127.0.0.1:1/"), db, mappers, errors, prefs, Dispatchers.IO)
        val scheduler = object : SyncScheduler { override fun requestSync() { syncRequests.value++ } }
        repo = ChatRepositoryImpl(api, db, mappers, errors, FakeAuth(session), scheduler, outbox, Dispatchers.Default)
    }

    @After fun tearDown() {
        db.close()
        server.close()
        scope.cancel()
    }

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun answer(sessionId: String, messageId: String = "m-${UUID.randomUUID()}") = jsonResponse(
        """{"session_id":"$sessionId","message_id":"$messageId","created_at":"2026-11-03T18:20:11Z",
           "answer":"The **761A00-11300000/A** delivers 700 in-lbs.","status":"answered",
           "products":[{"part_number":"761A00-11300000/A","role":"match","enclosure":"weatherproof",
             "certifications":["CSA","CE"],"csa_certified":true,
             "power_supply":{"voltage_v":110,"phase":1,"current_type":"ac","label":"110V single phase AC"},
             "motor_power_w":15,"data_available":true,
             "ratings":[{"service_type":"on_off","torque_inlb":700,"torque_nm":80,"duty_cycle_pct":40,"cycles_per_hour":36}],
             "electrical":[{"frequency_hz":60,"operating_speed_s":17,"fla_a":1.2,"lra_a":1.4}],
             "source":{"table":"WEATHERPROOF","page":1}}],
           "suggestions":[]}""",
    )

    @Test fun `offline message is cached as pending, then the outbox sends it`() = runBlocking {
        val sid = repo.sendMessage(null, "  What is the torque of 761A00-11300000/A?  ")
        assertTrue(syncRequests.value > 0)

        // Offline: shown immediately from the cache, waiting to be sent.
        val session = repo.observeSessions().first().single()
        assertEquals(sid, session.id)
        assertEquals(SyncState.PENDING_CREATE, session.syncState)
        val pending = repo.observeMessages(sid).first().single()
        assertEquals(SendState.PENDING, pending.sendState)
        assertEquals(OutboxProcessor.Outcome.RETRY_LATER, offlineOutbox.flush())
        assertEquals(SendState.PENDING, repo.observeMessages(sid).first().single().sendState)

        // Back online.
        server.enqueue(answer(sid, "srv-1"))
        assertEquals(OutboxProcessor.Outcome.DONE, outbox.flush())
        val request = server.takeRequest()
        val body = json.decodeFromString<Map<String, String?>>(request.body!!.utf8())
        assertEquals(sid, body["session_id"]) // the backend creates the session with the client's id
        assertEquals(pending.clientMessageId, body["client_message_id"]) // idempotent retries
        assertEquals("What is the torque of 761A00-11300000/A?", body["query"])

        val messages = repo.observeMessages(sid).first()
        assertEquals(2, messages.size)
        assertEquals(SendState.SENT, messages[0].sendState)
        assertEquals("srv-1", messages[1].id)
        assertEquals(700, messages[1].products.single().ratings.single().torqueInLb) // numbers from the server's DB
        assertEquals(SyncState.SYNCED, repo.observeSessions().first().single().syncState)
    }

    @Test fun `server errors mark the message as failed and retry re-queues it`() = runBlocking {
        val sid = repo.sendMessage(null, "hello")
        server.enqueue(jsonResponse("""{"error":{"code":"LLM_UNAVAILABLE","message":"The model is not available"}}""", 502))
        assertEquals(OutboxProcessor.Outcome.DONE, outbox.flush())
        val failed = repo.observeMessages(sid).first().single()
        assertEquals(SendState.FAILED, failed.sendState)
        assertEquals("The model is not available", failed.errorMessage)

        repo.retryMessage(failed.id)
        assertEquals(SendState.PENDING, repo.observeMessages(sid).first().single().sendState)
        server.enqueue(answer(sid))
        outbox.flush()
        assertEquals(listOf(SendState.SENT, SendState.SENT), repo.observeMessages(sid).first().map { it.sendState })
    }

    @Test fun `refresh merges the server list without losing offline changes`() = runBlocking {
        val sessions = db.sessionDao()
        sessions.upsert(SessionEntity("old", user.id, "Gone on server", 1, 1, 2, null, SyncState.SYNCED))
        sessions.upsert(SessionEntity("deleting", user.id, "Deleted offline", 1, 1, 2, null, SyncState.PENDING_DELETE))
        val offlineSid = repo.sendMessage(null, "created offline")
        server.enqueue(
            jsonResponse(
                """{"items":[
                  {"id":"s2","title":"Server chat","created_at":"2026-11-03T18:00:00Z","updated_at":"2026-11-03T19:00:00Z","message_count":4,"last_message_preview":"hi"},
                  {"id":"deleting","title":"Deleted offline","created_at":"2026-11-03T18:00:00Z","updated_at":"2026-11-03T18:00:00Z","message_count":2}
                ],"total":2}""",
            ),
        )
        assertTrue(repo.refreshSessions().isSuccess)
        val visible = repo.observeSessions().first().map { it.id }.toSet()
        assertEquals(setOf("s2", offlineSid), visible) // "old" removed, pending delete stays hidden
        assertEquals(SyncState.PENDING_DELETE, sessions.get("deleting")!!.syncState)
    }

    @Test fun `refresh of messages replaces synced history and keeps pending ones`() = runBlocking {
        db.sessionDao().upsert(SessionEntity("s1", user.id, "Chat", 1, 1, 0, null, SyncState.SYNCED))
        repo.sendMessage("s1", "follow-up written offline")
        server.enqueue(
            jsonResponse(
                """{"session_id":"s1","title":"Chat","messages":[
                  {"id":"u1","role":"user","content":"first","created_at":"2026-11-03T18:00:00Z"},
                  {"id":"a1","role":"assistant","content":"answer","status":"not_found","suggestions":["761A00-11300000/A"],"created_at":"2026-11-03T18:00:05Z"}
                ]}""",
            ),
        )
        assertTrue(repo.refreshMessages("s1").isSuccess)
        val messages = repo.observeMessages("s1").first()
        assertEquals(listOf("u1", "a1"), messages.take(2).map { it.id })
        assertEquals(listOf("761A00-11300000/A"), messages[1].suggestions)
        assertEquals(SendState.PENDING, messages.last().sendState)
    }

    @Test fun `deletion is hidden at once and synced later (404 counts as done)`() = runBlocking {
        db.sessionDao().upsert(SessionEntity("s1", user.id, "Chat", 1, 1, 0, null, SyncState.SYNCED))
        repo.deleteSession("s1")
        assertTrue(repo.observeSessions().first().isEmpty())
        assertEquals(OutboxProcessor.Outcome.RETRY_LATER, offlineOutbox.flush())
        assertEquals(SyncState.PENDING_DELETE, db.sessionDao().get("s1")!!.syncState)

        server.enqueue(jsonResponse("""{"error":{"code":"SESSION_NOT_FOUND","message":"Session not found"}}""", 404))
        assertEquals(OutboxProcessor.Outcome.DONE, outbox.flush())
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals(null, db.sessionDao().get("s1"))
    }
}
