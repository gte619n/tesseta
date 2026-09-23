import Charts
import SwiftUI
// import SharedCore  // ProgressionConsoleViewModel, its State / PatternReviewRow /
//                    // StrengthRow / ActiveGoal, BlockParameters, EnergyBalance — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D(iii) — the progression console.
/// Parity target (Android): `feature-workouts/.../progression/ProgressionConsoleScreen.kt`
/// + `ProgressionConsoleViewModel`. Observes the SHARED
/// `ProgressionConsoleViewModel` — the progression MATH (per-hand→total,
/// realistic jumps, demonstrated-override) is the backend engine's and the
/// presentation derivation (per-hand ×2 display, load-trend formatting, the
/// current→proposed set string, pinned-vs-measured divergence) is single-sourced
/// in that shared VM. This view only renders the prebaked rows + draws the
/// strength curve via Swift Charts.
struct ProgressionConsoleView: View {

    // MARK: Local mirrors of the shared state (deleted post-0D).

    struct PatternReviewRow: Identifiable {
        let id: String            // pattern
        let patternLabel: String
        let trend: String
        let setTargetChange: String
        let deload: Bool
        let reasoning: String
    }

    struct StrengthRow: Identifiable {
        let id: String            // exerciseId
        let name: String
        let movementPattern: String?
        let perHand: Bool
        let displayLoad: String
        let displayLbs: Double     // numeric, for the chart
        let perHandCaption: String?
        let confidence: String
    }

    struct Block {
        let mode: String
        let successCriterion: String
        let loadTrend: String       // prebaked "+0.7 lb / week"
        let manualOverride: Bool
        let repRanges: [(String, String)]
        let weeklyCeiling: [(String, String)]
    }

    struct ActiveGoal { let title: String; let domain: String }

    enum ScreenState {
        case loading
        case ready(week: [PatternReviewRow], strength: [StrengthRow], block: Block?,
                   goal: ActiveGoal?, measuredMode: String?, pinnedDivergence: Bool, updatingMode: Bool)
        case error(String)
    }

    static let modes = ["GAINING", "RECOMP", "MAINTENANCE", "RECOVERY"]

    @State private var state: ScreenState = .loading

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Progression Engine")
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(ProgressionConsoleViewModel(...))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn't load progression", systemImage: "chart.line.uptrend.xyaxis",
                                   description: Text(message))
        case .ready(let week, let strength, let block, let goal, let measuredMode, let pinnedDivergence, let updatingMode):
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    weekSection(week)
                    strengthSection(strength)
                    if let block { blockSection(block, goal: goal, measuredMode: measuredMode,
                                                pinnedDivergence: pinnedDivergence, updatingMode: updatingMode) }
                }
                .padding()
                .formMaxWidth()
            }
        }
    }

    // MARK: This week

    private func weekSection(_ rows: [PatternReviewRow]) -> some View {
        SettingsCard(title: "This week",
                     description: "Per-pattern volume trend and the set target the engine proposes.") {
            if rows.isEmpty {
                Text("Log a few weeks of training to see per-pattern trends.")
                    .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            } else {
                ForEach(rows) { row in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(row.patternLabel).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
                            Spacer()
                            pill(row.trend, tone: trendTone(row.trend))
                            if row.deload { pill("Deload", tone: Theme.warn) }
                        }
                        HStack(spacing: 6) {
                            Text("SETS").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                            Text(row.setTargetChange).font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
                        }
                        if !row.reasoning.isEmpty {
                            Text(row.reasoning).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
        }
    }

    // MARK: Estimated strength (Swift Charts curve + list)

    private func strengthSection(_ rows: [StrengthRow]) -> some View {
        SettingsCard(title: "Estimated strength",
                     description: "Estimated 1-rep max per lift — what your working weights are derived from.") {
            if rows.isEmpty {
                Text("Log working sets and the engine estimates your 1-rep max per lift.")
                    .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            } else {
                Chart(rows) { row in
                    BarMark(
                        x: .value("Load", row.displayLbs),
                        y: .value("Lift", row.name)
                    )
                    .foregroundStyle(Theme.accent)
                    .annotation(position: .trailing) {
                        Text(row.displayLoad).font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                    }
                }
                .chartXAxisLabel("lb")
                .frame(height: CGFloat(rows.count) * 44 + 24)

                ForEach(rows) { row in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                            if let caption = row.perHandCaption {
                                Text(caption).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                            } else if let pattern = row.movementPattern {
                                Text(pattern.uppercased()).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                            }
                        }
                        Spacer()
                        Text(row.displayLoad).font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
                        pill(row.confidence, tone: confidenceTone(row.confidence))
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }

    // MARK: Training block

    private func blockSection(_ block: Block, goal: ActiveGoal?, measuredMode: String?,
                              pinnedDivergence: Bool, updatingMode: Bool) -> some View {
        SettingsCard(title: "Training block",
                     description: "The mode driving progression, plus rep ranges and weekly set ceilings.") {
            VStack(alignment: .leading, spacing: 12) {
                // Mode selector (2×2), measured mode tagged.
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 6) {
                    ForEach(Self.modes, id: \.self) { mode in
                        modeCell(mode, selected: block.mode == mode, measured: measuredMode == mode,
                                 enabled: !updatingMode)
                    }
                }
                if pinnedDivergence, let measuredMode {
                    Text("Pinned to \(humanize(block.mode)) — your recent eating measures as \(humanize(measuredMode)).")
                        .font(.hfBodySm).foregroundStyle(Theme.warn)
                }
                if let goal {
                    Text("Goal: \(goal.title) (\(humanize(goal.domain)))")
                        .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                }

                keyValue("Success criterion", humanize(block.successCriterion))
                keyValue("Expected load trend", block.loadTrend)

                if !block.repRanges.isEmpty {
                    Text("REP RANGES").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                    ForEach(block.repRanges, id: \.0) { keyValue(humanize($0.0), $0.1) }
                }
                if !block.weeklyCeiling.isEmpty {
                    Text("WEEKLY SET CEILING").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                    ForEach(block.weeklyCeiling, id: \.0) { keyValue(humanize($0.0), $0.1) }
                }
            }
        }
    }

    private func modeCell(_ mode: String, selected: Bool, measured: Bool, enabled: Bool) -> some View {
        VStack(spacing: 2) {
            HStack(spacing: 4) {
                if selected { Image(systemName: "checkmark").font(.hfCapsSm).foregroundStyle(Theme.textInverse) }
                Text(humanize(mode)).font(.hfCapsSm)
                    .foregroundStyle(selected ? Theme.textInverse : Theme.textSecondary)
            }
            if measured {
                Text("measured").font(.system(size: 9, weight: .medium))
                    .foregroundStyle(selected ? Theme.textInverse : Theme.accentDim)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(selected ? Theme.accent : .clear, in: RoundedRectangle(cornerRadius: 7))
        .contentShape(Rectangle())
        .onTapGesture { if enabled { /* vm.updateMode(mode) */ } }
        .opacity(enabled ? 1 : 0.6)
    }

    // MARK: Small helpers

    private func keyValue(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(value).font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
        }
    }

    private func pill(_ text: String, tone: Color) -> some View {
        Text(text).font(.hfCapsSm).foregroundStyle(tone)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(tone.opacity(0.14), in: Capsule())
    }

    private func trendTone(_ trend: String) -> Color {
        switch trend.uppercased() {
        case "RISING": return Theme.good
        case "FALLING": return Theme.alert
        default: return Theme.neutral
        }
    }

    private func confidenceTone(_ confidence: String) -> Color {
        switch confidence.uppercased() {
        case "HIGH": return Theme.good
        case "LOW": return Theme.warn
        default: return Theme.neutral
        }
    }

    /// "PUSH_HORIZONTAL" → "Push Horizontal" (keeps acronyms upper-cased). The
    /// authoritative version is the shared VM's `humanize`; this is the render-
    /// side mirror for the already-mapped labels the VM doesn't pre-map.
    private func humanize(_ raw: String) -> String {
        let acronyms: Set<String> = ["RIR", "RPE", "1RM"]
        return raw.split(separator: "_").map { word -> String in
            acronyms.contains(word.uppercased()) ? word.uppercased() : word.capitalized
        }.joined(separator: " ")
    }
}

/// Pure-Swift mirrors of the SHARED `ProgressionConsoleViewModel` presentation
/// math (the authoritative version is Kotlin; these render-side helpers exist so
/// the iOS view compiles + is testable before the XCFramework lands, and are
/// pinned to the shared logic by `ProgressionFormatTests`). Post-0D the view can
/// consume the VM's prebaked rows directly and this can be deleted.
enum ProgressionFormat {

    /// A per-hand implement (dumbbell / dual cable) reports the TOTAL lifted (×2).
    static func isPerHand(name: String) -> Bool {
        let n = name.lowercased()
        return n.contains("dumbbell") || n.contains("db ") || n.hasPrefix("db")
            || n.contains("dual cable") || n.contains("dual-cable")
    }

    /// The displayed load for a lift, applying the per-hand→total ×2 rule.
    static func displayLoad(perHandE1rm: Double, name: String) -> String {
        let lbs = isPerHand(name: name) ? Int((perHandE1rm * 2).rounded()) : Int(perHandE1rm.rounded())
        return "\(lbs) lb"
    }

    /// The per-hand caption ("2 × 45 lb / hand"), or nil for non-per-hand lifts.
    static func perHandCaption(perHandE1rm: Double, name: String) -> String? {
        guard isPerHand(name: name) else { return nil }
        return "2 × \(Int(perHandE1rm.rounded())) lb / hand"
    }

    /// The block's expected e1RM drift → a signed per-week load trend string.
    static func loadTrend(driftPerDay: Double) -> String {
        let perWeek = driftPerDay * 7
        if abs(perWeek) < 0.05 { return "Holding — no planned change" }
        let sign = perWeek > 0 ? "+" : "−"
        return "\(sign)\(String(format: "%.1f", abs(perWeek))) lb / week"
    }
}

#Preview {
    NavigationStack {
        ProgressionConsoleView()
    }
}
