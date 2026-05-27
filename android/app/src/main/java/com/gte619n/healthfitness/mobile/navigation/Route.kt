package com.gte619n.healthfitness.mobile.navigation

import kotlinx.serialization.Serializable

/**
 * Single source of truth for the app's navigation surface. Every top-level
 * tab and every detail leaf added by later IMPLs is declared here; the
 * `NavHost` in `AppNavGraph` is the only place that knows how to render
 * each route, and any new destination must be added here first.
 *
 * Routes are `kotlinx.serialization`-driven so Navigation-Compose 2.8 can
 * round-trip the type-safe `Route` values without a manual `NavType`
 * adapter for primitive args.
 */
@Serializable
sealed interface Route {

    @Serializable
    data object Today : Route

    @Serializable
    data object Body : Route

    @Serializable
    data object Blood : Route

    @Serializable
    data object Workouts : Route

    @Serializable
    data object Medications : Route

    @Serializable
    data object Settings : Route

    // IMPL-AND-02: profile is a sub-screen of Settings (back stack:
    // SignIn → SignedInScaffold → Settings → Profile). Kept as a
    // top-level route in the sealed hierarchy so the type-safe NavGraph
    // can register it alongside the rest.
    @Serializable
    data object Profile : Route

    // Detail leaves wired by later IMPLs but declared here so the graph is
    // the single source of truth for the route surface.

    @Serializable
    data class DexaDetail(val scanId: String) : Route

    @Serializable
    data class BloodReportDetail(val reportId: String) : Route

    @Serializable
    data class MedicationDetail(val medicationId: String) : Route

    @Serializable
    data class GymDetail(val gymId: String) : Route
}
