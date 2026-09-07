package com.gte619n.healthfitness.data.withings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WithingsOAuthCoordinatorTest {

    @Test
    fun buildAuthorizeUrlEncodesParams() {
        val url = WithingsOAuthCoordinator.buildAuthorizeUrl("client-123", "state-xyz")

        assertEquals(
            "https://account.withings.com/oauth2_user/authorize2" +
                "?response_type=code" +
                "&client_id=client-123" +
                "&scope=user.metrics%2Cuser.activity" +
                "&redirect_uri=healthfitness%3A%2F%2Fwithings-callback" +
                "&state=state-xyz",
            url,
        )
    }

    @Test
    fun stateIsConsumedExactlyOnce() {
        val coordinator = WithingsOAuthCoordinator()
        coordinator.rememberState("s1")

        assertEquals("s1", coordinator.consumeState())
        // A second read returns null — a replayed callback can't reuse the state.
        assertNull(coordinator.consumeState())
    }
}
