package com.gte619n.healthfitness.mobile.auth

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPL-STAB (Workstream C) — the offline-first launch contract: a returning user
 * is shown the app immediately from their cached session and is never bounced to
 * the sign-in screen by a stale access token or a transient refresh failure.
 */
class AuthCoordinatorTest {

    private val repo = mockk<GoogleAuthRepository>(relaxed = true)
    private val cache = mockk<IdTokenCache>()

    private fun snapshot(
        idToken: String?,
        accessExpiresInSeconds: Long,
        hasSignedIn: Boolean,
        refreshUsable: Boolean = true,
    ): IdTokenCache.Snapshot {
        val nowSec = System.currentTimeMillis() / 1000
        return IdTokenCache.Snapshot(
            idToken = idToken,
            expiresAtEpochSeconds = nowSec + accessExpiresInSeconds,
            refreshToken = if (refreshUsable) "refresh" else null,
            refreshExpiresAtEpochSeconds = if (refreshUsable) nowSec + 60 * 60 * 24 else 0,
            hasSignedIn = hasSignedIn,
        )
    }

    @Test
    fun `never signed in goes to SignedOut`() = runTest {
        coEvery { cache.read() } returns snapshot(idToken = null, accessExpiresInSeconds = 0, hasSignedIn = false)
        val coordinator = AuthCoordinator(repo, cache)

        coordinator.bootstrap()

        assertEquals(AuthState.SignedOut, coordinator.state.value)
    }

    @Test
    fun `fresh cached token shows the app with no network`() = runTest {
        coEvery { cache.read() } returns snapshot(idToken = "tok", accessExpiresInSeconds = 3600, hasSignedIn = true)
        val coordinator = AuthCoordinator(repo, cache)

        coordinator.bootstrap()

        val state = coordinator.state.value
        assertTrue(state is AuthState.SignedIn)
        assertEquals("tok", (state as AuthState.SignedIn).idToken)
    }

    @Test
    fun `stale cached token still shows the app immediately (no Loading, no bounce)`() = runTest {
        // Access token expired, but the user has signed in before — render the app
        // on the cached session and refresh in the background. A transient refresh
        // failure must NOT flip the user out.
        coEvery { cache.read() } returns
            snapshot(idToken = "stale", accessExpiresInSeconds = -10, hasSignedIn = true)
        coEvery { repo.silentRefresh() } returns AuthState.Failed("offline")
        val coordinator = AuthCoordinator(repo, cache)

        coordinator.bootstrap()

        val state = coordinator.state.value
        assertTrue("returning user is shown the app, not Loading/SignedOut", state is AuthState.SignedIn)
        assertEquals("stale", (state as AuthState.SignedIn).idToken)
    }
}
