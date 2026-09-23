import Foundation
import SwiftUI
// import SharedCore   // KMP core via SKIE; XCFramework declared in project.yml.

/// Thin observable over the shared KMP `SyncEngine` (IMPL-IOS-01 Phase 2C).
/// **STUB** — the intended API and wiring are pinned here; the SKIE-bridged Flow
/// collection lands in 2C once the XCFramework is present.
///
/// Parity map to Android:
///   - `firstSyncComplete` gates the "Setting up" screen (Android SettingUpScreen).
///   - foreground-activation pull: always pull on scenePhase → .active (D8) —
///     the mitigation for iOS silent-push throttling (D7).
///   - push (D7): silent `sync` FCM data messages arrive as `content-available`
///     APNs pushes → trigger a delta pull. User-visible pushes
///     (leftover/adjust-review) carry their own sync trigger.
///   - background (D8): BGAppRefreshTask does a best-effort periodic delta pull;
///     BGProcessingTask drains the outbox when connectivity returns.
///
/// The engine itself (cursor, LWW, tombstones, schemaVersion resync, outbox
/// replay with Idempotency-Key) is entirely shared KMP — this bridge only maps
/// its Flows to SwiftUI `@Observable` state.
@Observable
@MainActor
final class SyncBridge {
    /// Mirrors the shared SyncEngine's `firstSyncComplete` StateFlow. Seeded
    /// `false`; the shell shows `FirstSyncGateView` until the first full delta
    /// pull applies. In 2A there is no engine, so a persisted flag stands in.
    var firstSyncComplete: Bool = UserDefaults.standard.bool(forKey: "firstSyncComplete")

    enum State: Equatable { case idle, syncing, pendingWrites, offline, failed }
    var state: State = .idle

    // MARK: - Intended 2C API (stubbed)

    /// Begin observing the shared engine's Flows via SKIE (AsyncSequence).
    /// ```swift
    /// let engine: SyncEngine = appState.sharedComponent.syncEngine
    /// for await complete in engine.firstSyncComplete {   // SKIE: Flow -> AsyncSequence
    ///     self.firstSyncComplete = complete.boolValue
    /// }
    /// ```
    func start() {
        // TODO(2C): collect engine.state + engine.firstSyncComplete via SKIE.
    }

    /// Foreground-activation pull (D8). Call from `.onChange(of: scenePhase)`.
    func pullOnForeground() {
        // TODO(2C): engine.requestPull(reason: .foreground)
    }

    /// Handle a silent `sync` push (D7). Called from the APNs
    /// `content-available` delivery.
    func handleSilentSyncPush() {
        // TODO(2C): engine.requestPull(reason: .push)
    }

    /// Register FCM token with the backend (D7):
    /// `PUT /api/me/devices/fcm` — registry is platform-agnostic (unchanged).
    func registerPushToken(_ token: String) {
        // TODO(2C): shared repository call to PUT /api/me/devices/fcm.
    }
}
