import Foundation
import SharedCore  // KMP core via SKIE (StateFlow -> AsyncSequence, sealed -> enum)

/// IMPL-IOS-01 Phase 3 — the ONE bridge from a shared KMP `StateFlow<T>` to
/// SwiftUI. SKIE (Touchlab) exposes a `StateFlow<T>` to Swift as an
/// `AsyncSequence` and its `value` as a plain property; this wrapper subscribes
/// on the main actor and republishes into an `@Observable` so any SwiftUI view
/// can `@State`/`@Environment` it and re-render on emission.
///
/// Every Phase 3 feature view uses this pattern rather than hand-rolling a
/// Combine publisher per screen — the shared ViewModel is the single source of
/// UI state (D2), the view is a pure function of it.
///
/// Usage (see MedicationsListView.swift):
/// ```
/// @State private var vm = ObservableViewModel(MedicationsViewModel(repo))
/// ...
/// switch vm.state { case .loading: ProgressView() ... }
/// ```
@MainActor
@Observable
final class ObservableViewModel<VM: AnyObject> {
    let wrapped: VM

    init(_ wrapped: VM) {
        self.wrapped = wrapped
    }

    /// Subscribe a shared `StateFlow` (exposed by SKIE as an `AsyncSequence`)
    /// and drive `apply` on every emission on the main actor. Call from
    /// `.task { }` so it cancels with the view's lifetime.
    ///
    /// The concrete `flow` type is `SkieSwiftStateFlow<State>` once the
    /// XCFramework is generated; kept generic here so this compiles pre-framework.
    func observe<State, S: AsyncSequence>(
        _ flow: S,
        apply: @escaping (State) -> Void
    ) async where S.Element == State {
        do {
            for try await value in flow {
                apply(value)
            }
        } catch {
            // StateFlow never completes/throws in practice; swallow cancellation.
        }
    }
    // Subscriptions are owned by the caller's SwiftUI `.task { }`, which cancels
    // them with the view's lifetime — so this wrapper holds no Task to tear down.
}
