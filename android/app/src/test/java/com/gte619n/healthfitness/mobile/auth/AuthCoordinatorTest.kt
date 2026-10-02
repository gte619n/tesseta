package com.gte619n.healthfitness.mobile.auth

import android.content.Context
import com.gte619n.healthfitness.data.auth.AccountStatusSignal
import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPL-STAB (Workstream C) — the offline-first launch contract: a returning user
 * is shown the app immediately from their cached session and is never bounced to
 * the sign-in screen by a stale access token or a transient refresh failure.
 *
 * Plus the multi-user account-switch guard: an interactive sign-in by a
 * different account than the one whose data is on this device wipes that data
 * before the new session renders anything.
 */
class AuthCoordinatorTest {

    private val repo = mockk<GoogleAuthRepository>(relaxed = true)
    private val cache = mockk<IdTokenCache>(relaxed = true)
    private val lastAccount = mockk<LastAccountStore>(relaxed = true)
    private val signOutSideEffects = mockk<SignOutSideEffects>(relaxed = true)
    // P1.4 — the real signal (a plain @Singleton SharedFlow holder) so the
    // coordinator's launch-time collector observes reported statuses.
    private val accountStatusSignal = AccountStatusSignal()

    private fun coordinator() =
        AuthCoordinator(repo, cache, lastAccount, signOutSideEffects, accountStatusSignal)

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
        val coordinator = coordinator()

        coordinator.bootstrap()

        assertEquals(AuthState.SignedOut, coordinator.state.value)
    }

    @Test
    fun `fresh cached token shows the app with no network`() = runTest {
        coEvery { cache.read() } returns snapshot(idToken = "tok", accessExpiresInSeconds = 3600, hasSignedIn = true)
        val coordinator = coordinator()

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
        val coordinator = coordinator()

        coordinator.bootstrap()

        val state = coordinator.state.value
        assertTrue("returning user is shown the app, not Loading/SignedOut", state is AuthState.SignedIn)
        assertEquals("stale", (state as AuthState.SignedIn).idToken)
    }

    @Test
    fun `interactive sign-in that succeeds sets a one-shot re-auth resync flag`() = runTest {
        coEvery { repo.interactiveSignIn(any()) } returns
            AuthState.SignedIn(userId = "u", email = null, displayName = null, idToken = "tok")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        // The signed-in entry consumes this to force a post-re-auth resync — and it
        // is one-shot, so a later cached launch / recomposition won't re-trigger it.
        assertTrue(coordinator.consumeInteractiveSignIn())
        assertFalse(coordinator.consumeInteractiveSignIn())
    }

    @Test
    fun `interactive sign-in that fails does not set the re-auth flag`() = runTest {
        coEvery { repo.interactiveSignIn(any()) } returns AuthState.Failed("cancelled")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        assertFalse(coordinator.consumeInteractiveSignIn())
    }

    @Test
    fun `a silent cached-session launch does not set the re-auth flag`() = runTest {
        coEvery { cache.read() } returns snapshot(idToken = "tok", accessExpiresInSeconds = 3600, hasSignedIn = true)
        val coordinator = coordinator()

        coordinator.bootstrap()

        // Only an explicit interactive sign-in forces the resync — a returning user
        // launching on their cached session must not pay for an extra pull.
        assertFalse(coordinator.consumeInteractiveSignIn())
    }

    // --- Multi-user account-switch guard -----------------------------------

    @Test
    fun `sign-in by a different account wipes the previous account's local data`() = runTest {
        every { lastAccount.read() } returns "user-a"
        coEvery { repo.interactiveSignIn(any()) } returns
            AuthState.SignedIn(userId = "user-b", email = null, displayName = null, idToken = "tok")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        coVerify(exactly = 1) { signOutSideEffects.wipeLocalData() }
        verify { lastAccount.write("user-b") }
        assertTrue(coordinator.state.value is AuthState.SignedIn)
    }

    @Test
    fun `sign-in by the same account does not wipe`() = runTest {
        every { lastAccount.read() } returns "user-a"
        coEvery { repo.interactiveSignIn(any()) } returns
            AuthState.SignedIn(userId = "user-a", email = null, displayName = null, idToken = "tok")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        coVerify(exactly = 0) { signOutSideEffects.wipeLocalData() }
        verify { lastAccount.write("user-a") }
    }

    @Test
    fun `first-ever sign-in records the account without wiping`() = runTest {
        every { lastAccount.read() } returns null
        coEvery { repo.interactiveSignIn(any()) } returns
            AuthState.SignedIn(userId = "user-a", email = null, displayName = null, idToken = "tok")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        coVerify(exactly = 0) { signOutSideEffects.wipeLocalData() }
        verify { lastAccount.write("user-a") }
    }

    @Test
    fun `undecodable identity skips the guard entirely`() = runTest {
        // "(session)" is GoogleAuthRepository's placeholder when token claims
        // couldn't be decoded — neither wipe nor record a bogus id on it.
        every { lastAccount.read() } returns "user-a"
        coEvery { repo.interactiveSignIn(any()) } returns
            AuthState.SignedIn(userId = "(session)", email = null, displayName = null, idToken = "tok")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        coVerify(exactly = 0) { signOutSideEffects.wipeLocalData() }
        verify(exactly = 0) { lastAccount.write(any()) }
    }

    @Test
    fun `failed sign-in never touches the guard`() = runTest {
        coEvery { repo.interactiveSignIn(any()) } returns AuthState.Failed("cancelled")
        val coordinator = coordinator()

        coordinator.interactiveSignIn(mockk<Context>(relaxed = true))

        coVerify(exactly = 0) { signOutSideEffects.wipeLocalData() }
        verify(exactly = 0) { lastAccount.write(any()) }
    }

    // --- P1.4 account-status lockout routing -------------------------------
    //
    // The coordinator's status collector runs on its own application scope
    // (Dispatchers.Default), not the test dispatcher, so [awaitState] polls the
    // StateFlow briefly rather than advancing virtual time.

    @Test
    fun `account-pending routes to Pending without wiping local data`() = runTest {
        val coordinator = coordinator()

        accountStatusSignal.report(AccountStatusSignal.Status.PENDING)

        assertTrue(awaitState(coordinator) { it is AuthState.Pending })
        // Pending is pre-approval: no local PHI to protect, so no wipe.
        coVerify(exactly = 0) { signOutSideEffects.wipeLocalData() }
    }

    @Test
    fun `account-suspended routes to Suspended and wipes local data`() = runTest {
        val coordinator = coordinator()

        accountStatusSignal.report(AccountStatusSignal.Status.SUSPENDED)

        assertTrue(
            awaitState(coordinator) { it is AuthState.Suspended && !it.disabled },
        )
        coVerify(exactly = 1) { signOutSideEffects.wipeLocalData() }
        coVerify(exactly = 1) { cache.clear() }
    }

    @Test
    fun `account-disabled routes to Suspended(disabled) and wipes local data`() = runTest {
        val coordinator = coordinator()

        accountStatusSignal.report(AccountStatusSignal.Status.DISABLED)

        assertTrue(
            awaitState(coordinator) { it is AuthState.Suspended && it.disabled },
        )
        coVerify(exactly = 1) { signOutSideEffects.wipeLocalData() }
    }

    /**
     * Poll the coordinator's StateFlow on a REAL dispatcher. The status collector
     * runs on the coordinator's own Dispatchers.Default scope (real threads), so
     * `runTest`'s virtual clock can't drive it — a virtual `delay` would spin
     * without yielding wall-clock time. Hop to Dispatchers.IO and sleep for real.
     */
    private suspend fun awaitState(
        coordinator: AuthCoordinator,
        timeoutMillis: Long = 2_000,
        predicate: (AuthState) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate(coordinator.state.value)) return@withContext true
            Thread.sleep(10)
        }
        predicate(coordinator.state.value)
    }
}
