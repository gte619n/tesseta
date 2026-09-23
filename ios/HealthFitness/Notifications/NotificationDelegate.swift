import Foundation
import UserNotifications
// import SharedCore  // AdherenceRepository, LocalReminderScheduler input — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave B — D9 `UNUserNotificationCenterDelegate` for the
/// medication reminder category.
///
/// Routes the three category actions and the body tap:
///  - Take    → log the dose through the shared adherence rail, then re-plan so the
///              reminder drops out (parity with Android's notification "✓" action,
///              minus the receiver-lifetime hazard — no `goAsync` window here).
///  - Snooze  → re-schedule this dose 15 min out (a fresh `UNTimeIntervalNotification
///              Trigger`); no domain write.
///  - Dismiss → clear it; the next replan won't resurrect a taken/edited dose.
///  - Body tap → deep-link `healthfitness://dose-checklist/{medicationId}` handed to
///              the app router, which pushes `MedicationsRoute.todaysDoses` (or the
///              med detail when a single med is carried in userInfo).
///
/// The deep-link routing is surfaced through `onDeepLink`, wired by the app to the
/// same navigation path the in-app `NavigationLink(value: MedicationsRoute...)` uses.
@MainActor
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {

    /// Called with a `healthfitness://dose-checklist/{medicationId}` URL when a
    /// medication reminder is tapped/actioned. Empty path component ⇒ open the full
    /// checklist (multiple doses were carried over).
    var onDeepLink: ((URL) -> Void)?

    /// Log a dose taken (the shared adherence rail). STUBBED where SharedCore isn't
    /// built; the real body calls `AdherenceRepository.logDose(medicationId:window:)`
    /// then `LocalReminderScheduler.shared.replan(...)`.
    var onTake: ((_ medicationId: String, _ window: String) -> Void)?

    /// Show a foreground reminder as a banner+sound too (don't silently swallow it).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    /// Handle an action / body tap.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let medicationId = userInfo[LocalReminderScheduler.Identifiers.userInfoMedicationId] as? String ?? ""

        MainActor.assumeIsolated {
            switch response.actionIdentifier {
            case LocalReminderScheduler.Identifiers.actionTake:
                handleTake(medicationId: medicationId, request: response.notification.request)
            case LocalReminderScheduler.Identifiers.actionSnooze:
                snooze(request: response.notification.request, center: center)
            case LocalReminderScheduler.Identifiers.actionDismiss:
                // Nothing to persist — the pending request already fired; the next
                // replan won't re-add a taken/edited dose. (Deliberately no "remember
                // dismissed keys" state: unlike Android there is no rolling engine to
                // resurrect it, so the dismissal is inherently honored.)
                break
            case UNNotificationDefaultActionIdentifier:
                // Body tap → deep link.
                if let url = Self.deepLink(medicationId: medicationId) {
                    onDeepLink?(url)
                }
            default:
                break
            }
            completionHandler()
        }
    }

    // MARK: Actions

    private func handleTake(medicationId: String, request: UNNotificationRequest) {
        guard !medicationId.isEmpty else {
            // A multi-dose "outstanding" post has no single med — open the checklist.
            if let url = Self.deepLink(medicationId: "") { onDeepLink?(url) }
            return
        }
        let window = Self.window(from: request)
        onTake?(medicationId, window)
        // Re-plan so this dose's future notification (and the outstanding post) drop.
        // Post-0D: LocalReminderScheduler.shared.replan(plan: scheduler.plannedDoses())
    }

    private func snooze(request: UNNotificationRequest, center: UNUserNotificationCenter) {
        let content = request.content.mutableCopy() as! UNMutableNotificationContent
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 15 * 60, repeats: false)
        let snoozed = UNNotificationRequest(
            identifier: request.identifier + ".snoozed",
            content: content,
            trigger: trigger,
        )
        center.add(snoozed)
    }

    // MARK: Helpers

    /// `healthfitness://dose-checklist/{medicationId}` (empty path ⇒ full checklist).
    static func deepLink(medicationId: String) -> URL? {
        var comps = URLComponents()
        comps.scheme = LocalReminderScheduler.Identifiers.deepLinkScheme
        comps.host = LocalReminderScheduler.Identifiers.deepLinkHost
        comps.path = medicationId.isEmpty ? "/" : "/\(medicationId)"
        return comps.url
    }

    /// The window label is the middle segment of our request id
    /// ("med-reminder.{medId}:{window}:{day}").
    static func window(from request: UNNotificationRequest) -> String {
        let id = request.identifier.replacingOccurrences(
            of: LocalReminderScheduler.Identifiers.requestPrefix, with: "")
        let parts = id.split(separator: ":")
        return parts.count >= 2 ? String(parts[1]) : ""
    }
}
