package com.gte619n.healthfitness.shared.ios

import com.gte619n.healthfitness.shared.data.ios.NSUserDefaultsUnitPreferencesRepository
import com.gte619n.healthfitness.shared.presentation.settings.UnitsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 1C — the iOS composition root (DI) + the hand-rolled Flow
 * bridge. SKIE is disabled (incompatible with Xcode 26), so instead of surfacing
 * Kotlin `Flow` as a Swift `AsyncSequence`, the SwiftUI layer subscribes through
 * [collectFlow] and republishes emissions into `@State`.
 *
 * Exposed to Swift as `IosComposition.shared` (it's a Kotlin `object`); it grows
 * one factory per wired screen as Phase 1C repositories land.
 */
object IosComposition {

    /** Settings › Units — on-device unit preferences (NSUserDefaults-backed). */
    fun unitsViewModel(): UnitsViewModel =
        UnitsViewModel(NSUserDefaultsUnitPreferencesRepository())

    /**
     * Subscribe to a Kotlin [Flow] from Swift. Collection runs on the main
     * dispatcher and each emission is handed to [onEach]; the returned
     * [FlowSubscription] is cancelled by the view on disappear. Emissions arrive
     * as the ObjC-bridged element type (the Swift caller casts). This is the
     * SKIE-free replacement for StateFlow → AsyncSequence.
     */
    fun <T : Any> collectFlow(flow: Flow<T>, onEach: (T) -> Unit): FlowSubscription {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val job: Job = scope.launch { flow.collect { onEach(it) } }
        return FlowSubscription(job)
    }
}

/** Cancellation handle for an [IosComposition.collectFlow] subscription. */
class FlowSubscription internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}
