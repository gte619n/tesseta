import Foundation
// import SharedCore  // ProgramFormat (shared) — the single source of truth, Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — LOCAL MIRROR of the shared
/// `presentation/workouts/ProgramFormat.kt` set/rep/weight/duration formatters.
/// Anti-drift: the shared Kotlin functions are the SSOT; this Swift mirror exists
/// ONLY so the SwiftUI views can format prior-performance / prescription lines
/// before the XCFramework is built. Post-0D these call the SKIE-bridged shared
/// functions and this file is deleted. `WorkoutFormatTests` pins these outputs to
/// the shared behavior (identical strings), so any drift fails a test.
enum WorkoutFormat {

    /// One logged set, e.g. "135 lb × 8", "BW × 12", "60s", "8 reps", "—".
    struct LoggedSet {
        var weightLbs: Double?
        var reps: Int?
        var durationSeconds: Int?
    }

    /// "ACTIVE" → "Active", "AI_GENERATED" → "Ai Generated". Simple humanizer for
    /// program/scheduled status enum names (mirrors the shared humanize()).
    static func statusLabel(_ enumName: String) -> String {
        enumName
            .split(separator: "_")
            .map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }
            .joined(separator: " ")
    }

    /// "Mon · Wed · Fri" — mirrors shared `trainingDaysSummary`. Empty → "".
    /// Input is the UPPERCASE wire day names ("MON"…).
    static func trainingDaysSummary(_ dayNames: [String]) -> String {
        dayNames
            .map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }
            .joined(separator: " · ")
    }

    private static let shortDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEE M/d"
        return f
    }()

    /// "Mon 9/22" day-and-date label for a scheduled session.
    static func dateLabel(_ date: Date) -> String {
        shortDateFormatter.string(from: date).uppercased()
    }

    static func trimNumber(_ value: Double) -> String {
        value == value.rounded() && abs(value) < 1e15
            ? String(Int(value))
            : String(value)
    }

    static func durationLabel(_ seconds: Int) -> String {
        if seconds % 60 == 0 { return "\(seconds / 60) min" }
        if seconds >= 60 { return "\(seconds / 60)m \(seconds % 60)s" }
        return "\(seconds)s"
    }

    static func loggedSetLabel(_ s: LoggedSet) -> String {
        let weight: String?
        if let lbs = s.weightLbs {
            weight = lbs == 0 ? "BW" : "\(trimNumber(lbs)) lb"
        } else {
            weight = nil
        }
        let reps = s.reps.map { "\($0)" }
        if let weight, let reps { return "\(weight) × \(reps)" }
        if let weight { return weight }
        if let reps { return "\(reps) reps" }
        if let dur = s.durationSeconds { return durationLabel(dur) }
        return "—"
    }

    /// "135 lb × 8 (×2) · 135 lb × 7" — consecutive identical sets collapsed with
    /// "(×N)". Mirrors shared `loggedSetsSummary`. Empty → nil.
    static func loggedSetsSummary(_ sets: [LoggedSet]) -> String? {
        guard !sets.isEmpty else { return nil }
        let formatted = sets.map { loggedSetLabel($0) }
        var parts: [String] = []
        var i = 0
        while i < formatted.count {
            var j = i + 1
            while j < formatted.count && formatted[j] == formatted[i] { j += 1 }
            let count = j - i
            parts.append(count > 1 ? "\(formatted[i]) (×\(count))" : formatted[i])
            i = j
        }
        return parts.joined(separator: " · ")
    }
}
