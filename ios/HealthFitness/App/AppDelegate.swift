import BackgroundTasks
import FirebaseCore
import FirebaseMessaging
import SharedCore
import UIKit

/// IMPL-IOS-01 (#4) — background execution + push, driven entirely through the shared
/// `IosComposition` sync graph (no AppState coupling, so the system can invoke these
/// before/without the SwiftUI scene).
///
/// - **BGTask (D8):** the two Info.plist-declared identifiers — `…refresh`
///   (BGAppRefreshTask → delta pull) and `…processing` (BGProcessingTask → outbox
///   drain on connectivity). Registered at launch; (re)scheduled when the app
///   backgrounds (see `HealthFitnessApp`).
/// - **Push (D7):** silent `sync` data pushes (`content-available`) → a delta pull +
///   drain; FCM token registration → `PUT /api/me/devices/fcm`. Firebase is only
///   configured when a `GoogleService-Info.plist` ships in the bundle, so builds
///   without that config (dev/TestFlight pre-provisioning) run BGTask + foreground
///   pull unaffected and simply don't receive pushes. (APNs auth key + the config
///   file + a real device are the remaining deployment steps.)
final class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate {

    static let refreshTaskId = "com.gte619n.healthfitness.refresh"
    static let processingTaskId = "com.gte619n.healthfitness.processing"

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        registerBackgroundTasks()
        configurePushIfAvailable()
        return true
    }

    // MARK: - Background tasks (D8)

    private func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTaskId, using: nil) { task in
            self.handleRefresh(task)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.processingTaskId, using: nil) { task in
            self.handleProcessing(task)
        }
    }

    /// Submit both requests (idempotent — the scheduler coalesces). Call when backgrounding.
    func scheduleBackgroundWork() {
        let refresh = BGAppRefreshTaskRequest(identifier: Self.refreshTaskId)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(refresh)

        let processing = BGProcessingTaskRequest(identifier: Self.processingTaskId)
        processing.requiresNetworkConnectivity = true
        try? BGTaskScheduler.shared.submit(processing)
    }

    /// BGAppRefreshTask → delta pull. Chains the next request, runs under an
    /// expiration guard, reports completion. The engine is resolved INSIDE the task so
    /// the non-Sendable `SyncEngine` isn't sent across the actor hop (Swift 6).
    private func handleRefresh(_ task: BGTask) {
        scheduleBackgroundWork()
        let op = Task {
            var ok = false
            if IosComposition.shared.isConfigured() {
                ok = ((try? await IosComposition.shared.syncEngine().pull()) != nil)
            }
            task.setTaskCompleted(success: ok)
        }
        task.expirationHandler = { op.cancel() }
    }

    /// BGProcessingTask → outbox drain (when connectivity is available).
    private func handleProcessing(_ task: BGTask) {
        scheduleBackgroundWork()
        let op = Task {
            var ok = false
            if IosComposition.shared.isConfigured() {
                ok = ((try? await IosComposition.shared.syncEngine().drainOutbox()) != nil)
            }
            task.setTaskCompleted(success: ok)
        }
        task.expirationHandler = { op.cancel() }
    }

    // MARK: - Push (D7, guarded on Firebase config)

    private func configurePushIfAvailable() {
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            // No Firebase config in this build → push dormant (deployment step). BGTask
            // + foreground pull are unaffected.
            return
        }
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Messaging.messaging().apnsToken = deviceToken
    }

    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        // MessagingDelegate is a non-isolated protocol; the class is @MainActor via
        // UIApplicationDelegate, so this callback must be nonisolated. The shared
        // registration call is actor-independent.
        guard let fcmToken else { return }
        Task { try? await IosComposition.shared.registerPushToken(token: fcmToken) }
    }

    /// Silent `sync` push (content-available): pull the delta + drain the outbox.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any]
    ) async -> UIBackgroundFetchResult {
        guard IosComposition.shared.isConfigured() else { return .noData }
        let engine = IosComposition.shared.syncEngine()
        _ = try? await engine.pull()
        _ = try? await engine.drainOutbox()
        return .newData
    }
}
