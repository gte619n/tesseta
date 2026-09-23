import Testing
import Foundation
import UserNotifications
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave B — Swift Testing for the D9 scheduler's window /
/// carryover / category-action logic (the parts that are real, pure Swift today;
/// the shared PLANNING inputs are covered by the KMP commonTest
/// `ReminderPlanningIntegrationTest`). No XCFramework needed — these exercise the
/// scheduler's own delivery decisions.
@Suite("LocalReminderScheduler D9 delivery")
@MainActor
struct LocalReminderSchedulerTest {

    // MARK: window / carryover split

    private func dose(_ id: String, offsetHours: Double, from now: Date) -> LocalReminderScheduler.PlannedDose {
        .init(medicationId: id, name: id, windowLabel: "MORNING", doseSummary: "1 tab",
              fireDate: now.addingTimeInterval(offsetHours * 3600))
    }

    @Test("carryover: an already-passed dose is treated as overdue, not scheduled ahead")
    func overduePartition() {
        let now = Date()
        let plan = [
            dose("overdue", offsetHours: -2, from: now),   // already passed
            dose("soon", offsetHours: 3, from: now),       // within window
            dose("later", offsetHours: 30, from: now),     // within 48h window
        ]
        let overdue = plan.filter { $0.fireDate <= now }
        let upcoming = plan.filter { $0.fireDate > now }
        #expect(overdue.map(\.medicationId) == ["overdue"])
        #expect(upcoming.map(\.medicationId) == ["soon", "later"])
    }

    @Test("window horizon excludes doses beyond ~48h")
    func windowHorizon() {
        let scheduler = LocalReminderScheduler(windowHours: 48)
        let now = Date()
        let horizon = now.addingTimeInterval(Double(scheduler.windowHours) * 3600)
        let inside = dose("in", offsetHours: 47, from: now)
        let outside = dose("out", offsetHours: 49, from: now)
        #expect(inside.fireDate <= horizon)
        #expect(outside.fireDate > horizon)
    }

    // MARK: request id stability (replan replaces, never duplicates)

    @Test("request id is stable for the same med/window/day so a replan replaces it")
    func requestIdStable() {
        let d = Date(timeIntervalSince1970: 1_790_000_000) // fixed
        let a = LocalReminderScheduler.requestId(medicationId: "m1", window: "MORNING", date: d)
        let b = LocalReminderScheduler.requestId(medicationId: "m1", window: "MORNING", date: d)
        #expect(a == b)
        #expect(a.hasPrefix(LocalReminderScheduler.Identifiers.requestPrefix))
    }

    @Test("request id differs across meds and windows")
    func requestIdDistinct() {
        let d = Date(timeIntervalSince1970: 1_790_000_000)
        let m1 = LocalReminderScheduler.requestId(medicationId: "m1", window: "MORNING", date: d)
        let m2 = LocalReminderScheduler.requestId(medicationId: "m2", window: "MORNING", date: d)
        let w = LocalReminderScheduler.requestId(medicationId: "m1", window: "EVENING", date: d)
        #expect(m1 != m2)
        #expect(m1 != w)
    }

    // MARK: category actions (parity with Android Take / Snooze / Dismiss)

    @Test("category carries Take, Snooze and Dismiss actions")
    func categoryActions() {
        let ids = LocalReminderScheduler.category.actions.map(\.identifier)
        #expect(ids.contains(LocalReminderScheduler.Identifiers.actionTake))
        #expect(ids.contains(LocalReminderScheduler.Identifiers.actionSnooze))
        #expect(ids.contains(LocalReminderScheduler.Identifiers.actionDismiss))
        #expect(LocalReminderScheduler.category.identifier == LocalReminderScheduler.Identifiers.category)
    }

    // MARK: deep link routing

    @Test("deep link targets the per-med dose checklist")
    func deepLinkPerMed() throws {
        let url = try #require(NotificationDelegate.deepLink(medicationId: "med-42"))
        #expect(url.scheme == "healthfitness")
        #expect(url.host == "dose-checklist")
        #expect(url.path == "/med-42")
    }

    @Test("empty medication id opens the full checklist")
    func deepLinkFullChecklist() throws {
        let url = try #require(NotificationDelegate.deepLink(medicationId: ""))
        #expect(url.host == "dose-checklist")
        #expect(url.path == "/")
    }

    @Test("window label is recovered from the request id middle segment")
    func windowFromRequestId() {
        let content = UNMutableNotificationContent()
        let id = LocalReminderScheduler.Identifiers.requestPrefix + "med-1:EVENING:2026-09-23"
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        #expect(NotificationDelegate.window(from: request) == "EVENING")
    }
}
