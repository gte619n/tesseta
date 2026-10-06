import Foundation
import SharedCore
import UserNotifications

/// IMPL-IOS-01 Phase 3 Wave B — D9 medication reminder delivery for iOS.
///
/// DECISION D9 (from the plan): iOS does NOT port Android's `AlarmManager` +
/// `ReminderEngine` broadcast machinery. The scheduling *decisions* — which doses
/// are due on a date, at what resolved time, and what's carried over as
/// overdue-and-untaken — stay in the shared KMP `ReminderPlanner` /
/// `OutstandingDoses` (already extracted, commonTest-covered). Only *delivery* is
/// platform: this pre-schedules a rolling window of `UNCalendarNotificationTrigger`
/// notifications from that shared output. There is no background-exec engine to
/// keep alive, so the whole class of Android reminder bugs (network I/O in the
/// 10s `goAsync` window killing the receiver → zombie notifications, lost dismissal
/// state, 30s-apart duplicate posts) simply cannot occur — UNUserNotificationCenter
/// owns firing.
///
/// MECHANISM:
///  1. `replan(medications:settings:)` clears our pending requests and, for each
///     scheduled dose in the next `windowHours` (~48h) whose fire time is still in
///     the future, schedules one `UNCalendarNotificationTrigger` (non-repeating,
///     exact date components → the local clock fires it, honoring DST/timezone).
///     Doses already overdue at replan time (carryover) are surfaced immediately as
///     a single "outstanding" notification via `OutstandingDoses.outstanding`.
///  2. Re-planning is idempotent and driven by the app on: sync completion,
///     foreground (`willEnterForeground`), and a local-midnight boundary — the
///     three moments the underlying dose set or the day can change. Because we
///     always clear-then-reschedule, a dose taken/edited/muted anywhere drops its
///     future notification on the next replan.
///  3. A `UNNotificationCategory` ("MED_REMINDER") carries Take / Snooze / Dismiss
///     actions; `NotificationDelegate` handles them and routes the body tap through
///     the `healthfitness://dose-checklist/{medicationId}` deep link.
///
/// The planning INPUT is the shared `ReminderPlanner`/`OutstandingDoses` output;
/// the call sites are commented with `// SharedCore:` where the XCFramework isn't
/// built yet, and a local mirror (`PlannedDose`) stands in so this file is real,
/// compilable UserNotifications code today.
@MainActor
final class LocalReminderScheduler {

    static let shared = LocalReminderScheduler()

    /// How far ahead we pre-schedule. ~48h covers today + tomorrow so a missed
    /// replan (device off, app not foregrounded) still fires tomorrow's doses; the
    /// next foreground/sync replan extends the window again.
    let windowHours: Int
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current(), windowHours: Int = 48) {
        self.center = center
        self.windowHours = windowHours
    }

    // MARK: Setup

    /// Register the medication-reminder category + actions and request authorization.
    /// Call once at app start (before the first `replan`).
    func configure(delegate: UNUserNotificationCenterDelegate) {
        center.delegate = delegate
        center.setNotificationCategories([Self.category])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// The reminder category with Take / Snooze / Dismiss (parity with Android's
    /// per-med "✓" / "Take all" + dismissal).
    static let category = UNNotificationCategory(
        identifier: Identifiers.category,
        actions: [
            UNNotificationAction(identifier: Identifiers.actionTake, title: "Take",
                                 options: [.authenticationRequired]),
            UNNotificationAction(identifier: Identifiers.actionSnooze, title: "Snooze 15 min",
                                 options: []),
            UNNotificationAction(identifier: Identifiers.actionDismiss, title: "Dismiss",
                                 options: [.destructive]),
        ],
        intentIdentifiers: [],
        options: [],
    )

    // MARK: Planning

    /// Clear our pending medication reminders and reschedule the next `windowHours`
    /// from the shared planner output. Idempotent (clear-then-schedule), so a dose
    /// taken/edited/muted since the last plan drops out. Call on sync / foreground /
    /// midnight.
    ///
    /// - Parameters:
    ///   - plan: the shared-planner output for this window (see `plannedDoses`).
    ///   - now: injected for testing (defaults to the current instant).
    func replan(plan: [PlannedDose], now: Date = Date()) {
        clearPending()
        let horizon = now.addingTimeInterval(Double(windowHours) * 3600)

        // Carryover: doses whose time already passed but aren't taken → one
        // immediate "outstanding" notification (parity with the Android rolling
        // reminder). Everything strictly in the future → its own scheduled trigger.
        let overdue = plan.filter { $0.fireDate <= now }
        let upcoming = plan.filter { $0.fireDate > now && $0.fireDate <= horizon }

        if !overdue.isEmpty {
            scheduleOutstanding(overdue)
        }
        for dose in upcoming {
            scheduleDose(dose)
        }
    }

    /// Remove only OUR pending requests (leave any non-medication notifications).
    func clearPending() {
        center.getPendingNotificationRequests { requests in
            let ids = requests
                .map(\.identifier)
                .filter { $0.hasPrefix(Identifiers.requestPrefix) }
            self.center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }

    private func scheduleDose(_ dose: PlannedDose) {
        let content = makeContent(
            title: dose.name,
            body: "\(dose.doseSummary) · \(dose.windowLabel)",
            medicationId: dose.medicationId,
        )
        var comps = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute], from: dose.fireDate)
        comps.second = 0
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        let request = UNNotificationRequest(
            identifier: Self.requestId(medicationId: dose.medicationId, window: dose.windowLabel, date: dose.fireDate),
            content: content,
            trigger: trigger,
        )
        center.add(request)
    }

    /// A single immediate notification for the carried-over overdue set (fires now).
    private func scheduleOutstanding(_ overdue: [PlannedDose]) {
        let title = overdue.count == 1 ? "1 medication to take" : "\(overdue.count) medications to take"
        let body = overdue.map { "\($0.name) — \($0.doseSummary)" }.joined(separator: ", ")
        // Deep-link to the checklist rather than a single med when several are due.
        let deepLinkId = overdue.count == 1 ? overdue[0].medicationId : ""
        let content = makeContent(title: title, body: body, medicationId: deepLinkId)
        // nil trigger = deliver immediately.
        let request = UNNotificationRequest(
            identifier: Identifiers.requestPrefix + "outstanding",
            content: content,
            trigger: nil,
        )
        center.add(request)
    }

    private func makeContent(title: String, body: String, medicationId: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = Identifiers.category
        content.userInfo = [Identifiers.userInfoMedicationId: medicationId]
        return content
    }

    /// Stable per-dose request id so a replan replaces (not duplicates) a request.
    static func requestId(medicationId: String, window: String, date: Date) -> String {
        let day = ISO8601DateFormatter.dayOnly.string(from: date)
        return "\(Identifiers.requestPrefix)\(medicationId):\(window):\(day)"
    }

    // MARK: Identifiers

    enum Identifiers {
        static let category = "MED_REMINDER"
        static let requestPrefix = "med-reminder."
        static let actionTake = "MED_TAKE"
        static let actionSnooze = "MED_SNOOZE"
        static let actionDismiss = "MED_DISMISS"
        static let userInfoMedicationId = "medicationId"
        /// Deep link the body tap opens (parity with Android's
        /// `healthfitness://medications/today`; per-med form here).
        static let deepLinkScheme = "healthfitness"
        static let deepLinkHost = "dose-checklist"
    }

    // MARK: Shared-planner adapter (bridged input)

    /// A flattened, platform-ready dose to schedule. Built from the shared
    /// `OutstandingDoses.scheduledFor(...)` / `.outstanding(...)` output — the iOS
    /// side never re-derives due-dates or times. Mirrors the shared `DueDose`
    /// (medicationId / name / window / dose / unit / resolved time) plus the
    /// concrete `fireDate` we compute by combining the plan date with the resolved
    /// `LocalTime`.
    struct PlannedDose: Equatable {
        let medicationId: String
        let name: String
        let windowLabel: String
        let doseSummary: String   // "1 tab"
        let fireDate: Date
    }

    /// Fetch the window's doses from the shared planner
    /// (`IosComposition.plannedMedicationDoses` → `OutstandingDoses`) and map them
    /// into schedulable `PlannedDose`s. The shared side owns all due-date / window-
    /// time resolution; here we only convert the resolved fire instant to a `Date`.
    /// Returns `[]` on any failure (offline / signed out).
    func plannedDosesFromShared() async -> [PlannedDose] {
        let days = Int32((windowHours + 23) / 24)   // ceil to whole days
        let planned = (try? await IosComposition.shared.plannedMedicationDoses(days: days)) ?? []
        return planned.map { p in
            PlannedDose(
                medicationId: p.medicationId,
                name: p.name,
                windowLabel: p.windowLabel,
                doseSummary: p.doseSummary,
                fireDate: Date(timeIntervalSince1970: Double(p.fireEpochMs) / 1000.0),
            )
        }
    }

    /// Fetch the shared plan and reschedule. Call on sign-in, sync completion,
    /// foreground, and the local-midnight boundary — the moments the dose set or
    /// the day can change. Idempotent (clear-then-schedule).
    func replanFromShared(now: Date = Date()) async {
        replan(plan: await plannedDosesFromShared(), now: now)
    }
}

private extension ISO8601DateFormatter {
    // Configured once and only ever used read-only for formatting, which
    // ISO8601DateFormatter supports concurrently. `nonisolated(unsafe)` opts it
    // out of Swift 6's Sendable check (the type isn't marked Sendable, but this
    // shared, immutable-after-setup instance is safe to read from any thread).
    nonisolated(unsafe) static let dayOnly: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withFullDate]
        return f
    }()
}
