package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Round 2 Stage D coverage for the gym list / grid row. Three snapshots
 * pinning the production-reachable variants:
 *
 *  - [withoutCoverPhoto] — fallback dumbbell icon centered in the cover
 *    placeholder, no default-gym star.
 *  - [defaultBadge] — default-gym star at the title row's trailing edge,
 *    fallback cover placeholder.
 *  - [withAmenitiesAndAddress] — full amenity chip row + address line,
 *    covers the bottom-of-card chip layout.
 *
 * The "with cover photo" path is intentionally NOT snapshot here:
 * `HfAsyncImage` delegates to Coil's `AsyncImage`, which spawns a
 * coroutine on `Dispatchers.Main` to decode the request — Paparazzi's
 * LayoutLib host has no Android main looper, so the request fails
 * before producing a deterministic image. Adding `coil-test`'s
 * `FakeImageLoaderEngine` would let us pin a real bitmap, but the
 * fallback / default-badge variants already exercise the card's
 * conditional UI — adding a `Bitmap` snapshot via a custom loader
 * is queued for a follow-up if/when the cover-photo geometry itself
 * needs visual regression coverage. Noted under
 * "Round 2 — Stage D" in docs/plans/android-impl-questions.md.
 */
class LocationCardPaparazziTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NOTNIGHT),
    )

    private val baseLocation = Location(
        locationId = "gym-1",
        name = "Iron Forge Gym",
        address = "1421 Main St, Austin TX",
        coverPhotoUrl = null,
        is24Hours = true,
        hours = null,
        amenities = emptyList(),
        equipmentIds = listOf("eq-1", "eq-2", "eq-3"),
        equipmentSpecs = emptyMap(),
        isDefault = false,
        isActive = true,
        createdAt = Instant.parse("2026-01-15T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-22T15:30:00Z"),
    )

    @Test
    fun withoutCoverPhoto() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                Box(Modifier.padding(16.dp).width(340.dp)) {
                    LocationCard(
                        location = baseLocation,
                        onClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun defaultBadge() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                Box(Modifier.padding(16.dp).width(340.dp)) {
                    LocationCard(
                        location = baseLocation.copy(
                            name = "Home Garage",
                            isDefault = true,
                        ),
                        onClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun withAmenitiesAndAddress() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                Box(Modifier.padding(16.dp).width(340.dp)) {
                    LocationCard(
                        location = baseLocation.copy(
                            name = "Downtown Fitness",
                            amenities = listOf(
                                Amenity.TWENTY_FOUR_HR,
                                Amenity.PARKING,
                                Amenity.SHOWERS,
                                Amenity.SAUNA,
                            ),
                        ),
                        onClick = {},
                    )
                }
            }
        }
    }
}
