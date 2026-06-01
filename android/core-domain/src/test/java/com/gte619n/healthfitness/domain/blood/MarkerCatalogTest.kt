package com.gte619n.healthfitness.domain.blood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerCatalogTest {

    @Test
    fun testosteroneDisplayName() {
        assertEquals("Testosterone", MarkerCatalog.displayName(BloodMarker.TESTOSTERONE))
    }

    @Test
    fun displayOrderListsTestosteroneFirst() {
        assertEquals(BloodMarker.TESTOSTERONE, MarkerCatalog.DISPLAY_ORDER.first())
    }

    @Test
    fun displayOrderHasNoDuplicates() {
        assertEquals(
            MarkerCatalog.DISPLAY_ORDER.size,
            MarkerCatalog.DISPLAY_ORDER.toSet().size,
        )
    }

    @Test
    fun displayOrderCoversEveryMarker() {
        assertEquals(
            BloodMarker.entries.toSet(),
            MarkerCatalog.DISPLAY_ORDER.toSet(),
        )
    }

    @Test
    fun catalogResolvesEveryMarker() {
        // displayName/description/target are exhaustive `when`s; calling each
        // for every enum value guards against a missing branch after edits.
        for (marker in BloodMarker.entries) {
            assertTrue(MarkerCatalog.displayName(marker).isNotBlank())
            assertTrue(MarkerCatalog.description(marker).isNotBlank())
            assertTrue(MarkerCatalog.target(marker).isNotBlank())
        }
    }
}
