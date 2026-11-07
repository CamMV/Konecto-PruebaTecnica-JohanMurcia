package com.konecto.series76.ui

import app.cash.turbine.test
import com.konecto.series76.core.AppError
import com.konecto.series76.domain.model.AuthSession
import com.konecto.series76.domain.model.ServerHealth
import com.konecto.series76.domain.model.User
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import com.konecto.series76.domain.repository.ServerConfigRepository
import com.konecto.series76.ui.feature.login.LoginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val session = AuthSession("t", Long.MAX_VALUE, User("u", "ana@konecto.test", "Ana"))
    private var loginResult: Result<AuthSession> = Result.success(session)
    private val calls = mutableListOf<Pair<String, String>>()

    private val auth = object : AuthRepository {
        override val session = MutableStateFlow<AuthSession?>(null)
        override val forcedLogout = MutableSharedFlow<Unit>()
        override suspend fun awaitLoaded(): AuthSession? = null
        override suspend fun login(email: String, password: String): Result<AuthSession> {
            calls += email to password
            return loginResult
        }
        override suspend fun validateToken() = Unit
        override suspend fun logout() = Unit
    }
    private val network = object : NetworkMonitor { override val isOnline = MutableStateFlow(true) }
    private val server = object : ServerConfigRepository {
        override val baseUrl = MutableStateFlow("http://10.0.2.2:8001/")
        override fun normalize(input: String) = input
        override suspend fun testConnection(url: String) = Result.success(ServerHealth(true, true, 68))
        override suspend fun setBaseUrl(url: String) = Result.success(Unit)
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `blank fields show an error without calling the API`() = runTest {
        val vm = LoginViewModel(auth, network, server)
        vm.uiState.test {
            awaitItem()
            vm.submit()
            assertEquals("Enter your username and password", awaitItem().error)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `successful login emits the navigation event and clears the password`() = runTest {
        val vm = LoginViewModel(auth, network, server)
        vm.onEmailChange("ana@konecto.test")
        vm.onPasswordChange("Series76!demo")
        vm.loggedIn.test {
            vm.submit()
            awaitItem()
        }
        assertEquals(listOf("ana@konecto.test" to "Series76!demo"), calls)
        assertEquals("", vm.password)
    }

    @Test fun `unreachable server explains which URL failed`() = runTest {
        loginResult = Result.failure(AppError.Network())
        val vm = LoginViewModel(auth, network, server)
        vm.onEmailChange("ana@konecto.test")
        vm.onPasswordChange("x")
        vm.uiState.test {
            awaitItem()
            vm.submit()
            val error = expectMostRecentItem().error.orEmpty()
            assertTrue(error, error.contains("http://10.0.2.2:8001/"))
        }
    }
}
