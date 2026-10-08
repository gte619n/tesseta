import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D — one program's detail, bound to the SHARED
/// `ProgramDetailViewModel` (deep tree + this-week/past strips + activate / edit /
/// apply-nutrition / delete-session / restore-parked, keyed by programId). Parity
/// target: Android `ProgramDetailScreen` + `ProgramDetailViewModel`.
///
/// Each day pushes `WorkoutsRoute.workoutDetail`; the resume banner deep-links the
/// in-progress logger (`WorkoutsRoute.session`). "Refine with AI" routes to the
/// designer once that agent wires `WorkoutsRoute.designer(programId:)`.
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

    private let vm: ProgramDetailViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?
    @State private var showEdit = false
    @State private var editTitle = ""
    @State private var editDescription = ""
    @State private var resumeTarget: ResumeTarget?

    private struct ResumeTarget: Identifiable, Hashable {
        let scheduledId: String
        var id: String { scheduledId }
    }

    init(programId: String) {
        self.programId = programId
        self.vm = IosComposition.shared.programDetailViewModel(programId: programId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.title.isEmpty ? "Program" : state.title)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("program-detail")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Edit details") {
                            editTitle = state.title
                            editDescription = state.description ?? ""
                            showEdit = true
                        }
                        if state.hasNutritionGuidance {
                            Button("Apply nutrition target") { vm.applyNutrition() }
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .sheet(isPresented: $showEdit) { editSheet }
            .navigationDestination(item: $resumeTarget) { t in
                WorkoutSessionView(programId: programId, scheduledId: t.scheduledId)
            }
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? ProgramDetailUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
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
                if let resume = state.resumeScheduledId {
                    Section {
                        Button {
                            resumeTarget = ResumeTarget(scheduledId: resume)
                        } label: {
                            Label("Resume your in-progress workout", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .accessibilityIdentifier("resume-\(programId)-\(resume)")
                    }
                }
                if !state.activationIssues.isEmpty {
                    Section("Can’t activate yet") {
                        ForEach(state.activationIssues, id: \.self) { issue in
                            Label(issue, systemImage: "exclamationmark.triangle")
                                .font(.hfBodySm).foregroundStyle(Theme.warn)
                        }
                    }
                } else if state.status != "Active" {
                    Section {
                        Button("Activate program") { vm.activate() }
                            .buttonStyle(.borderedProminent).tint(Theme.accent)
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

    private var editSheet: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $editTitle)
                TextField("Description", text: $editDescription, axis: .vertical)
            }
            .navigationTitle("Edit program")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showEdit = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        vm.saveEdit(title: editTitle,
                                    description: editDescription.isEmpty ? nil : editDescription)
                        showEdit = false
                    }
                    .disabled(editTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    // MARK: - Mapping

    private static func map(_ s: ProgramDetailUiState) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.error = s.error
        out.activationIssues = s.activationIssues
        out.resumeScheduledId = s.activeDraft?.scheduledId
        if let program = s.program {
            out.title = program.title
            out.description = program.description_
            out.goalTitle = program.goalTitle
            out.status = WorkoutFormat.statusLabel(program.status.name)
            out.phases = program.phases.map { phase in
                PhaseSection(
                    id: phase.phaseId,
                    title: phase.title,
                    focus: phase.focus,
                    days: phase.days.map { day in
                        DayRow(
                            id: day.dayId,
                            phaseId: phase.phaseId,
                            dayId: day.dayId,
                            label: day.label,
                            detail: exerciseCountLabel(day),
                            completed: false,
                        )
                    },
                )
            }
        }
        out.hasNutritionGuidance = s.nutritionGuidance.map { !$0.isEmpty } ?? false
        out.thisWeek = s.thisWeek.map { mapScheduled($0, phaseFallback: s.program) }
        out.pastSessions = s.pastSessions.map { mapScheduled($0, phaseFallback: s.program) }
        return out
    }

    private static func mapScheduled(
        _ sw: SharedCore.ScheduledWorkout,
        phaseFallback: SharedCore.WorkoutProgram?,
    ) -> DayRow {
        DayRow(
            id: sw.scheduledId,
            phaseId: sw.phaseId,
            dayId: sw.dayId,
            label: sw.dayLabel,
            detail: WorkoutFormat.dateLabel(localDateToDate(sw.date)),
            completed: sw.status == SharedCore.ScheduledStatus.completed,
        )
    }

    private static func exerciseCountLabel(_ day: SharedCore.WorkoutDay) -> String {
        let n = day.blocks.reduce(0) { $0 + $1.prescriptions.count }
        switch n {
        case 0: return "No exercises"
        case 1: return "1 exercise"
        default: return "\(n) exercises"
        }
    }

    private static func localDateToDate(_ d: Kotlinx_datetimeLocalDate) -> Date {
        Date(timeIntervalSince1970: Double(d.toEpochDays()) * 86_400)
    }
}
