package com.gte619n.healthfitness.shared.presentation.settings

import com.gte619n.healthfitness.shared.data.AppInfo
import com.gte619n.healthfitness.shared.data.AuthRepository
import com.gte619n.healthfitness.shared.data.WithingsOAuthCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — shared SettingsViewModel test, ported from the
 * Android `feature-settings/.../SettingsViewModel.kt` behavior. Also covers the
 * pure [WithingsOAuthCoordinator.buildAuthorizeUrl] string builder (shared with
 * the Android original) so the OAuth URL never drifts across clients.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAuthRepository : AuthRepository {
        var signedOut = false
        override suspend fun signOut() {
            signedOut = true
        }
    }

    @Test
    fun exposesBuildVersion() {
        val vm = SettingsViewModel(FakeAuthRepository(), AppInfo("1.4.2", 142))
        assertEquals("1.4.2", vm.versionName)
        assertEquals(142, vm.versionCode)
    }

    @Test
    fun signOutClearsSessionThenCallsBack() = runTest {
        val auth = FakeAuthRepository()
        val vm = SettingsViewModel(auth, AppInfo("1.0.0", 1))

        var done = false
        vm.signOut { done = true }

        assertTrue(auth.signedOut)
        assertTrue(done)
    }

    @Test
    fun authorizeUrlCarriesAllOAuthParams() {
        val url = WithingsOAuthCoordinator.buildAuthorizeUrl(
            clientId = "abc123",
            state = "xyz",
        )
        assertTrue(url.startsWith(WithingsOAuthCoordinator.AUTHORIZE_URL))
        assertContains(url, "response_type=code")
        assertContains(url, "client_id=abc123")
        assertContains(url, "state=xyz")
        // The redirect URI's "://" must be percent-encoded, and the scope comma too.
        assertContains(url, "redirect_uri=healthfitness%3A%2F%2Fwithings-callback")
        assertContains(url, "scope=user.metrics%2Cuser.activity")
    }
}
