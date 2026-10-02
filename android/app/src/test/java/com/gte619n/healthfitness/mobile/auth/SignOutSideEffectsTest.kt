package com.gte619n.healthfitness.mobile.auth

import coil.ImageLoader
import com.gte619n.healthfitness.data.dashboard.RecentActivityCache
import com.gte619n.healthfitness.data.db.DbWipe
import com.gte619n.healthfitness.data.medications.TodaysDosesCache
import com.gte619n.healthfitness.data.net.HttpCacheWipe
import com.gte619n.healthfitness.data.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.mobile.push.TokenRegistration
import dagger.Lazy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * IMPL-MULTIUSER-01 DL-AND-5 — the lockout/sign-out wipe must flush the outbox
 * drain barrier BEFORE deleting the encrypted DB.
 *
 * [SignOutSideEffects.wipeLocalData] drops the whole encrypted Room mirror,
 * outbox table included. The suspend/disable lockout ([AuthCoordinator]) and the
 * account-switch guard both route through this one method, so the cross-module
 * barrier lives here: it awaits any in-flight [OutboxRepository.drain] (via
 * [OutboxRepository.awaitDrainIdle]) before [DbWipe.wipe], so the final in-flight
 * drain completes and the wipe can't delete rows out from under it.
 */
class SignOutSideEffectsTest {

    private val tokenRegistration = mockk<TokenRegistration>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val dbWipe = mockk<DbWipe>(relaxed = true)
    private val recentActivityCache = mockk<RecentActivityCache>(relaxed = true)
    private val todaysDosesCache = mockk<TodaysDosesCache>(relaxed = true)
    private val unitPreferences = mockk<UnitPreferencesRepository>(relaxed = true)
    private val httpCacheWipe = mockk<HttpCacheWipe>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private fun sideEffects() = SignOutSideEffects(
        tokenRegistration = Lazy { tokenRegistration },
        outbox = Lazy { outbox },
        dbWipe = dbWipe,
        recentActivityCache = recentActivityCache,
        todaysDosesCache = todaysDosesCache,
        unitPreferences = unitPreferences,
        httpCacheWipe = httpCacheWipe,
        imageLoader = imageLoader,
        context = context,
    )

    @Test
    fun `wipeLocalData awaits the drain barrier before dropping the encrypted DB`() = runTest {
        coEvery { outbox.awaitDrainIdle() } just Runs
        coEvery { dbWipe.wipe() } just Runs

        sideEffects().wipeLocalData()

        // The ordering guarantee (DL-AND-5): flush the in-flight drain, THEN wipe.
        coVerifyOrder {
            outbox.awaitDrainIdle()
            dbWipe.wipe()
        }
    }

    @Test
    fun `a failing drain barrier never blocks the PHI wipe`() = runTest {
        // Best-effort: the barrier is a safety serialization, not a precondition —
        // if awaiting it throws, the encrypted DB must still be wiped.
        coEvery { outbox.awaitDrainIdle() } throws IllegalStateException("boom")
        coEvery { dbWipe.wipe() } just Runs

        sideEffects().wipeLocalData()

        coVerifyOrder {
            outbox.awaitDrainIdle()
            dbWipe.wipe()
        }
    }
}
