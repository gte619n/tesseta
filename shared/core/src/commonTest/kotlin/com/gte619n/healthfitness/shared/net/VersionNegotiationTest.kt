package com.gte619n.healthfitness.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals

/** IMPL-IOS-01 Phase 0B — XPLAT-002 handshake logic, shared/tested once. */
class VersionNegotiationTest {

    @Test
    fun headerFormat() {
        assertEquals("ios/142", clientHeader(ClientPlatform.IOS, 142))
        assertEquals("android/873", clientHeader(ClientPlatform.ANDROID, 873))
    }

    @Test
    fun upgradeRequiredOn426() {
        assertEquals(
            VersionVerdict.UPGRADE_REQUIRED,
            VersionNegotiation.verdict(426, null),
        )
    }

    @Test
    fun recommendedHeaderIsNonBlocking() {
        assertEquals(
            VersionVerdict.UPGRADE_RECOMMENDED,
            VersionNegotiation.verdict(200, "recommended"),
        )
    }

    @Test
    fun normalResponseIsOk() {
        assertEquals(VersionVerdict.OK, VersionNegotiation.verdict(200, null))
    }
}
