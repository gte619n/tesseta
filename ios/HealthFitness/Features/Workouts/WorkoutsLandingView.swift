import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D — the read-first "This Week" landing, bound to the
/// SHARED `WorkoutsHubViewModel` (KMP port of Android's `WorkoutsLandingViewModel`).
/// The compliance + streak maths are derived in shared `ComplianceMath`, never
/// here; this view is a pure function of the shared UI state.
///
/// Sections: the featured program header, the current-week strip, the streak /
/// weekly-progress line, resume / recovery banners, and quick links out to
/// Programs / History / Library. Empty states cover "no program yet" vs. "no
/// active program".
struct WorkoutsLandingView: View {

    struct ScreenState {
        var loading = true
        var hasAnyProgram = true
        var programTitle: String?
        var programId: String?
        var thisWeek: [SessionRow] = []
        var weekStreak = 0
        var completedThisWeek = 0
        var weeklyStreakTarget = 3
        var resumeScheduledId: String?
        var parkedDayLabel: String?
        var error: String?
    }

    struct SessionRow: Identifiable {
        let id: String        // scheduledId
        let dateLabel: String // "MON 9/22"
        let dayLabel: String
        let completed: Bool
        let programId: String
    }

    private let vm: WorkoutsHubViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?
    @State private var resumeTarget: ResumeTarget?

    private struct ResumeTarget: Identifiable, Hashable {
        let programId: String
        let scheduledId: String
        var id: String { "\(programId)/\(scheduledId)" }
    }

    init() {
        self.vm = IosComposition.shared.workoutsHubViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .accessibilityIdentifier("workouts-landing")
            .navigationDestination(item: $resumeTarget) { t in
                WorkoutSessionView(programId: t.programId, scheduledId: t.scheduledId)
            }
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? WorkoutsHubUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView("Couldn’t load your training", systemImage: "dumbbell",
                                   description: Text(error))
        } else if !state.hasAnyProgram {
            noProgramEmptyState
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let title = state.programTitle, let pid = state.programId {
                        programHeader(title: title, programId: pid)
                    }
                    resumeBannerIfNeeded
                    streakLine
                    thisWeekSection
                    quickLinks
                }
                .padding()
                .formMaxWidth()
            }
            .refreshable { vm.refresh() }
        }
    }

    private var noProgramEmptyState: some View {
        VStack(spacing: 12) {
            ContentUnavailableView("No training yet", systemImage: "figure.strengthtraining.traditional",
                                   description: Text("Design a program or browse the library to get started."))
            NavigationLink("Browse the library", value: WorkoutsRoute.library)
                .font(.hfBodyMd)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func programHeader(title: String, programId: String) -> some View {
        NavigationLink(value: WorkoutsRoute.programDetail(programId: programId)) {
            VStack(alignment: .leading, spacing: 4) {
                Text("FEATURED PROGRAM").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Text(title).font(.hfHeadingLg).foregroundStyle(Theme.textPrimary)
            }
        }
    }

    @ViewBuilder
    private var resumeBannerIfNeeded: some View {
        if let sched = state.resumeScheduledId, let pid = state.programId {
            Button {
                resumeTarget = ResumeTarget(programId: pid, scheduledId: sched)
            } label: {
                HStack {
                    Image(systemName: "arrow.triangle.2.circlepath")
                    Text("Resume your in-progress workout").font(.hfBodyMd)
                    Spacer()
                }
                .padding()
                .background(Theme.accentBg)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("resume-\(pid)-\(sched)")
        } else if let day = state.parkedDayLabel {
            HStack {
                Image(systemName: "exclamationmark.arrow.circlepath")
                Text("“\(day)” didn’t sync — restore to review").font(.hfBodySm)
                Spacer()
            }
            .padding()
            .background(Theme.warnBg)
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
    }

    private var streakLine: some View {
        HStack(spacing: 16) {
            statTile(value: "\(state.weekStreak)", label: "week streak")
            statTile(value: "\(state.completedThisWeek)/\(state.weeklyStreakTarget)", label: "this week")
        }
    }

    private func statTile(value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.hfDisplayMd).foregroundStyle(Theme.accent)
            Text(label).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var thisWeekSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("This week").font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            if state.thisWeek.isEmpty {
                Text("No sessions scheduled this week.").font(.hfBodySm)
                    .foregroundStyle(Theme.textSecondary)
            } else {
                ForEach(state.thisWeek) { row in
                    NavigationLink(value: WorkoutsRoute.programDetail(programId: row.programId)) {
                        HStack {
                            Image(systemName: row.completed ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(row.completed ? Theme.good : Theme.textQuaternary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.dayLabel).font(.hfBodyMd)
                                Text(row.dateLabel).font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
    }

    private var quickLinks: some View {
        VStack(spacing: 0) {
            NavigationLink(value: WorkoutsRoute.programs) { linkRow("All programs", "list.bullet.rectangle") }
            Divider()
            NavigationLink(value: WorkoutsRoute.history) { linkRow("Workout history", "clock.arrow.circlepath") }
            Divider()
            NavigationLink(value: WorkoutsRoute.library) { linkRow("Library", "books.vertical") }
        }
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func linkRow(_ title: String, _ icon: String) -> some View {
        HStack {
            Image(systemName: icon).foregroundStyle(Theme.accent)
            Text(title).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            Spacer()
            Image(systemName: "chevron.right").font(.hfCapsSm).foregroundStyle(Theme.textQuaternary)
        }
        .padding()
    }

    // MARK: - Mapping

    private static func map(_ s: WorkoutsHubUiState) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.error = s.error
        out.hasAnyProgram = s.hasAnyProgram
        out.programTitle = s.program?.title
        out.programId = s.program?.programId
        out.weekStreak = Int(s.weekStreak)
        out.completedThisWeek = Int(s.completedThisWeek)
        out.weeklyStreakTarget = Int(s.weeklyStreakTarget)
        out.resumeScheduledId = s.activeDraft?.scheduledId
        out.parkedDayLabel = s.parkedCompletion?.dayLabel
        let pid = s.program?.programId ?? ""
        out.thisWeek = s.thisWeek.map { sw in
            SessionRow(
                id: sw.scheduledId,
                dateLabel: WorkoutFormat.dateLabel(localDateToDate(sw.date)),
                dayLabel: sw.dayLabel,
                completed: sw.status == SharedCore.ScheduledStatus.completed,
                programId: pid,
            )
        }
        return out
    }

    private static func localDateToDate(_ d: Kotlinx_datetimeLocalDate) -> Date {
        Date(timeIntervalSince1970: Double(d.toEpochDays()) * 86_400)
    }
}
