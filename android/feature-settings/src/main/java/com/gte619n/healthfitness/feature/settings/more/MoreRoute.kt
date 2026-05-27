package com.gte619n.healthfitness.feature.settings.more

import kotlinx.serialization.Serializable

/**
 * Typed nav route for the phone's "More" tab. Lives in the feature
 * module so the route shape is owned by the screen that renders it,
 * matching the convention used by the medical / blood / workouts
 * feature routes (the legacy Route.Settings shim stays in `app/`
 * because nothing visible has migrated out yet).
 */
@Serializable
data object MoreRoute
