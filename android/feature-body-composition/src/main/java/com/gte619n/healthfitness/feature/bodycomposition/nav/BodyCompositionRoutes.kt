package com.gte619n.healthfitness.feature.bodycomposition.nav

import kotlinx.serialization.Serializable

/**
 * Type-safe nav routes for the body-composition feature. Mirrors the
 * `feature-blood` shape — overview is a stack root, detail carries the
 * scanId argument, upload is a dialog destination.
 *
 * Registered into the phone app's NavHost from `AppNavGraph`.
 */
@Serializable
data object BodyCompositionRoute

@Serializable
data class DexaScanDetailRoute(val scanId: String)

@Serializable
data object UploadDexaRoute
