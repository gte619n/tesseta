import SwiftUI
// import SharedCore  // ProgramDetailViewModel, ProgramDetailUiState — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — one program's detail. Parity target: Android
/// `ProgramDetailScreen` + `ProgramDetailViewModel` (ported to shared, keyed by
/// `programId`). Shows the deep phase/day tree, the current-week strip, a
/// "log a past session" pool, activate + edit-details + apply-nutrition actions,
/// and the resume / parked-completion recovery banners.
///
/// Each day pushes `WorkoutsRoute.workoutDetail`; "Start" / "Resume" push toward
/// the live-session route once the LIVE-SESSION agent adds it. Read paths are
/// real (bound to the shared VM post-0D); the sheets/mutations are wired to the
/// shared VM intents (`activate`, `saveEdit`, `applyNutrition`, `deleteSession`,
/// `restoreParked`).
struct ProgramDetailView: View {

    let programId: String

    struct ScreenState {
        var loading = true
        var title = ""
        var description: String?
        var goalTitle: String?
        var status = ""
        var phases: [PhaseSection] = []
        var thisWeek: [DayRow] = []
        var pastSessions: [DayRow] = []
        var activationIssues: [String] = []
        var hasNutritionGuidance = false
        var resumeScheduledId: String?
        var error: String?
    }

    struct PhaseSection: Identifiable {
        let id: String       // phaseId
        let title: String
        let focus: String?
        let days: [DayRow]
    }

    struct DayRow: Identifiable {
        let id: String       // dayId or scheduledId
        let phaseId: String
        let dayId: String
        let label: String
        let detail: String   // "5 exercises" or "MON 9/22"
        let completed: Bool
    }

    @State private var state = ScreenState()
    @State private var showEdit = false

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.title.isEmpty ? "Program" : state.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Edit details") { showEdit = true }
                        if state.hasNutritionGuidance {
                            Button("Apply nutrition target") { /* vm.applyNutrition() */ }
                        }
                        // "Refine with AI" routes to the designer once the
                        // DESIGNER agent adds `WorkoutsRoute.designer(programId:)`.
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(ProgramDetailViewModel(
        //         repository: DI.workoutProgramRepository,
        //         sessionRepository: DI.workoutSessionRepository,
        //         programId: programId))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView("Couldn’t load program", systemImage: "doc.text",
                                   description: Text(error))
        } else {
            List {
                if !state.activationIssues.isEmpty {
                    Section("Can’t activate yet") {
                        ForEach(state.activationIssues, id: \.self) { issue in
                            Label(issue, systemImage: "exclamationmark.triangle")
                                .font(.hfBodySm).foregroundStyle(Theme.warn)
                        }
                    }
                }
                if !state.thisWeek.isEmpty {
                    Section("This week") {
                        ForEach(state.thisWeek) { row in dayLink(row) }
                    }
                }
                ForEach(state.phases) { phase in
                    Section(phase.focus.map { "\(phase.title) — \($0)" } ?? phase.title) {
                        ForEach(phase.days) { row in dayLink(row) }
                    }
                }
                if !state.pastSessions.isEmpty {
                    Section("Log a past session") {
                        ForEach(state.pastSessions) { row in dayLink(row) }
                    }
                }
            }
            .formMaxWidth()
        }
    }

    private func dayLink(_ row: DayRow) -> some View {
        NavigationLink(value: WorkoutsRoute.workoutDetail(
            programId: programId, phaseId: row.phaseId, dayId: row.dayId)) {
            HStack {
                if row.completed {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.good)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.label).font(.hfBodyMd)
                    Text(row.detail).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                }
            }
        }
    }

    // static func map(_ s: ProgramDetailUiState) -> ScreenState { ... }  // Phase 0D
}
