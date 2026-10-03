import Foundation
import SwiftUI
import SharedCore

/// Persisted first-sync-gate flag key. File-level (not a `Self.` static) so it can
/// seed an `@Observable` stored property without the covariant-Self restriction.
private let syncGateKey = "firstSyncComplete"

/// Observable bridge over the shared KMP `SyncEngine` (IMPL-IOS-01 Phase E).
///
/// Drives the delta pull + outbox drain and surfaces `firstSyncComplete` to gate
/// the "Setting up" screen. Parity map to Android:
///   - `firstSyncComplete` gates `FirstSyncGateView` (Android SettingUpScreen).
///   - foreground-activation pull (D8): `pullOnForeground()` from scenePhase → .active.
///   - silent `sync` push (D7): `handleSilentSyncPush()` from the APNs
///     `content-available` delivery.
///   - BGTask periodic pull / outbox drain (D8): wire later; `syncNow()` is the body.
///
/// The engine itself (cursor, LWW, tombstones, schemaVersion resync, outbox replay
/// with Idempotency-Key) is shared KMP; this bridge only kicks it and republishes
/// `firstSyncComplete` into SwiftUI state. Engine `pull`/`drainOutbox` are Kotlin
/// suspend funcs, bridged to Swift `async` (no SKIE needed); `firstSyncComplete()`
/// is a Flow collected via `IosComposition.collectFlow`.
@Observable
@MainActor
final class SyncBridge {
    /// Mirrors the shared engine's `firstSyncComplete`. Seeded from the persisted
    /// flag so a returning user isn't re-gated; the engine flips it on first pull.
    var firstSyncComplete: Bool = UserDefaults.standard.bool(forKey: syncGateKey)

    enum State: Equatable { case idle, syncing, pendingWrites, offline, failed }
    var state: State = .idle

    private var subscription: FlowSubscription?

    /// Begin observing the engine's `firstSyncComplete` and kick the first sync.
    /// Called from `FirstSyncGateView.task` (signed-in, gate not yet cleared).
    func start() {
        let engine = IosComposition.shared.syncEngine()
        subscription?.cancel()
        subscription = IosComposition.shared.collectFlow(flow: engine.firstSyncComplete()) { [weak self] value in
            guard let self, let done = value as? KotlinBoolean, done.boolValue else { return }
            self.firstSyncComplete = true
            UserDefaults.standard.set(true, forKey: syncGateKey)
        }
        syncNow()
    }

    /// Foreground-activation pull (D8). Call from `.onChange(of: scenePhase)`.
    func pullOnForeground() {
        guard IosComposition.shared.isConfigured() else { return }
        syncNow()
    }

    /// Handle a silent `sync` push (D7): trigger a delta pull + drain.
    func handleSilentSyncPush() {
        guard IosComposition.shared.isConfigured() else { return }
        syncNow()
    }

    /// Register FCM token with the backend (D7): `PUT /api/me/devices/fcm`.
    /// (The push layer — `AppDelegate` — normally registers directly off the FCM
    /// callback; this is here for any in-app trigger.)
    func registerPushToken(_ token: String) {
        guard IosComposition.shared.isConfigured() else { return }
        Task { try? await IosComposition.shared.registerPushToken(token: token) }
    }

    /// Reset the gate + stop observing on sign-out so the next account re-syncs from
    /// scratch (the mirror/outbox/key wipe is done by `AuthState.signOut`).
    func reset() {
        subscription?.cancel()
        subscription = nil
        firstSyncComplete = false
        UserDefaults.standard.removeObject(forKey: syncGateKey)
        state = .idle
    }

    /// Pull the delta to convergence, then drain the outbox. Failures (incl. a
    /// signed-out 401) leave the bridge `.failed`; the gate still clears via the
    /// engine's `firstSyncComplete` once a pull succeeds.
    private func syncNow() {
        state = .syncing
        Task { @MainActor in
            // Resolve the engine INSIDE the task so the non-Sendable `any SyncEngine`
            // isn't sent across the actor hop (Swift 6 strict concurrency).
            let engine = IosComposition.shared.syncEngine()
            do {
                _ = try await engine.pull()
                _ = try await engine.drainOutbox()
                state = .idle
            } catch {
                state = .failed
            }
        }
    }
}
