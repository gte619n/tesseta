package com.gte619n.healthfitness.shared.ios

import com.gte619n.healthfitness.shared.data.ios.NSUserDefaultsUnitPreferencesRepository
import com.gte619n.healthfitness.shared.presentation.settings.UnitsViewModel

/**
 * IMPL-IOS-01 Phase 1C — the iOS composition root (DI). SKIE exposes this object
 * to Swift as `IosComposition.shared`, so the SwiftUI layer builds a shared
 * ViewModel with one call (`IosComposition.shared.unitsViewModel()`) instead of
 * knowing how to assemble its repository graph.
 *
 * It grows one factory per wired screen as Phase 1C repositories land; the first
 * vertical slice wires on-device unit preferences end to end.
 */
object IosComposition {

    /** Settings › Units — on-device unit preferences (NSUserDefaults-backed). */
    fun unitsViewModel(): UnitsViewModel =
        UnitsViewModel(NSUserDefaultsUnitPreferencesRepository())
}
