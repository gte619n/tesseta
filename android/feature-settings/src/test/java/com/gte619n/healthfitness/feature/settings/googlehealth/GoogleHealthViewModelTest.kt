package com.gte619n.healthfitness.feature.settings.googlehealth

import android.content.Intent
import android.content.IntentSender
import app.cash.turbine.test
import com.gte619n.healthfitness.data.auth.GoogleHealthScopeRepository
import com.gte619n.healthfitness.data.auth.HealthAuthFlow
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthRepository
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthStatus
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleHealthViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeHealthRepo(
        var statusResult: Result<GoogleHealthStatus>,
        var connectResult: Result<Unit> = Result.success(Unit),
        var disconnectResult: Result<Unit> = Result.success(Unit),
    ) : GoogleHealthRepository {
        var lastCode: String? = null
        var statusCalls = 0
        override suspend fun status(): Result<GoogleHealthStatus> {
            statusCalls++
            return statusResult
        }
        override suspend fun connectWithServerAuthCode(serverAuthCode: String): Result<Unit> {
            lastCode = serverAuthCode
            return connectResult
        }
        override suspend fun disconnect(): Result<Unit> = disconnectResult
    }

    // GoogleHealthScopeRepository is a concrete class; we substitute its
    // behavior by constructing it with a fake authorization client seam is
    // overkill here, so we use a relaxed mock for the two methods the VM calls.
    private fun fakeScope(
        authorize: () -> HealthAuthFlow,
        parse: (Intent?) -> HealthAuthFlow = { HealthAuthFlow.Failed("not used") },
    ): GoogleHealthScopeRepository {
        val mock = mockk<GoogleHealthScopeRepository>()
        io.mockk.coEvery { mock.requestHealthAuthorization() } answers { authorize() }
        io.mockk.every { mock.parseConsentResult(any()) } answers { parse(firstArg()) }
        return mock
    }

    @Test
    fun loadingThenDisconnected() = runTest {
        val repo = FakeHealthRepo(Result.success(GoogleHealthStatus(false, null)))
        val vm = GoogleHealthViewModel(repo, fakeScope({ HealthAuthFlow.Failed("n/a") }))

        vm.state.test {
            var s = awaitItem()
            while (s !is GoogleHealthViewModel.UiState.Disconnected) s = awaitItem()
            assertTrue(s is GoogleHealthViewModel.UiState.Disconnected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun connectResolvedImmediatelyConnects() = runTest {
        val repo = FakeHealthRepo(Result.success(GoogleHealthStatus(false, null)))
        // After connect succeeds, refresh() reads status again -> connected.
        val vm = GoogleHealthViewModel(repo, fakeScope({ HealthAuthFlow.Resolved("auth-123") }))

        vm.state.test {
            var s = awaitItem()
            while (s !is GoogleHealthViewModel.UiState.Disconnected) s = awaitItem()

            // flip status so the post-connect refresh reports connected
            repo.statusResult = Result.success(GoogleHealthStatus(true, 1700000000L))
            vm.connect()

            // Disconnected(connecting=true)
            val connecting = awaitItem()
            assertTrue((connecting as GoogleHealthViewModel.UiState.Disconnected).connecting)

            // submitAuthCode -> refresh() -> Loading -> Connected
            var next = awaitItem()
            while (next !is GoogleHealthViewModel.UiState.Connected) next = awaitItem()
            assertEquals(1700000000L, (next as GoogleHealthViewModel.UiState.Connected).connectedAtEpochSeconds)
            assertEquals("auth-123", repo.lastCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun connectNeedsConsentEmitsIntentSenderThenConnects() = runTest {
        val sender = mockk<IntentSender>()
        val repo = FakeHealthRepo(Result.success(GoogleHealthStatus(false, null)))
        val data = mockk<Intent>()
        val vm = GoogleHealthViewModel(
            repo,
            fakeScope(
                authorize = { HealthAuthFlow.NeedsUserConsent(sender) },
                parse = { HealthAuthFlow.Resolved("code-from-consent") },
            ),
        )

        vm.consentRequests.test {
            // wait for VM to settle on Disconnected
            vm.state.test {
                var s = awaitItem()
                while (s !is GoogleHealthViewModel.UiState.Disconnected) s = awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            vm.connect()
            assertSame(sender, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // flip status so post-consent refresh reports connected
        repo.statusResult = Result.success(GoogleHealthStatus(true, 1700000001L))
        vm.state.test {
            vm.onConsentResult(data)
            var s = awaitItem()
            while (s !is GoogleHealthViewModel.UiState.Connected) s = awaitItem()
            assertEquals("code-from-consent", repo.lastCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun disconnectTransitionsToDisconnected() = runTest {
        val repo = FakeHealthRepo(Result.success(GoogleHealthStatus(true, 1700000000L)))
        val vm = GoogleHealthViewModel(repo, fakeScope({ HealthAuthFlow.Failed("n/a") }))

        vm.state.test {
            var s = awaitItem()
            while (s !is GoogleHealthViewModel.UiState.Connected) s = awaitItem()

            vm.disconnect()

            val disconnecting = awaitItem()
            assertTrue((disconnecting as GoogleHealthViewModel.UiState.Connected).disconnecting)

            var next = awaitItem()
            while (next !is GoogleHealthViewModel.UiState.Disconnected) next = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun statusFailureSurfacesError() = runTest {
        val repo = FakeHealthRepo(Result.failure(RuntimeException("network")))
        val vm = GoogleHealthViewModel(repo, fakeScope({ HealthAuthFlow.Failed("n/a") }))

        vm.state.test {
            var s = awaitItem()
            while (s !is GoogleHealthViewModel.UiState.Error) s = awaitItem()
            assertEquals("network", (s as GoogleHealthViewModel.UiState.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
