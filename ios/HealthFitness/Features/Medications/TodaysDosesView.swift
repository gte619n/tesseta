import SwiftUI
import SharedCore

/// IMPL-IOS-01 — the dose checklist. Parity target: Android
/// `feature-medical/.../today/TodaysDosesScreen.kt` + `TodaysDosesViewModel`.
///
/// Backed by the SHARED `TodaysDosesViewModel` over the online-first
/// `HttpMedicationCrudRepository` (reactive today's-doses) + `HttpAdherenceRepository`
/// (log/undo). Toggling a dose routes to the VM, which writes adherence then kicks a
/// today-refresh so the single reactive source re-emits and the list updates (parity
/// with Android's mirror re-emit — no manual optimistic flip here). Observed via
/// `collectFlow` + a static `map(...)`, the same SKIE-free pattern as `MedicationsListView`.
///
/// This is ALSO the D9 local-notification deep-link target
/// (`healthfitness://dose-checklist/{id}` → `MedicationsRoute.todaysDoses`).
struct TodaysDosesView: View {

    enum ScreenState {
        case loading
        case ready([DoseRow])
        case error(String)
    }

    struct DoseRow: Identifiable {
        let id: String          // "medicationId:window"
        let dose: TodaysDose    // the shared type, for the toggle intent
        let name: String
        let window: String      // TimeWindow label
        let doseSummary: String // "1 mg"
        let taken: Bool
    }

    private let vm: TodaysDosesViewModel
    @State private var state: ScreenState = .loading
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.todaysDosesViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Today’s doses")
            .accessibilityIdentifier("todays-doses")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? TodaysDosesUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load doses", systemImage: "pills",
                                   description: Text(message))
        case .ready(let doses):
            if doses.isEmpty {
                ContentUnavailableView("Nothing due today", systemImage: "checkmark.circle",
                                       description: Text("You’re all caught up."))
            } else {
                List {
                    Section("Due today") {
                        ForEach(doses) { dose in
                            Button {
                                vm.toggle(dose: dose.dose)
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: dose.taken ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(dose.taken ? Theme.good : Theme.textTertiary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(dose.name).font(.hfBodyMd)
                                            .foregroundStyle(Theme.textPrimary)
                                        Text("\(dose.window) · \(dose.doseSummary)")
                                            .font(.hfBodySm)
                                            .foregroundStyle(Theme.textSecondary)
                                    }
                                    Spacer()
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .formMaxWidth()
            }
        }
    }

    // MARK: map

    private static func map(_ s: TodaysDosesUiState) -> ScreenState {
        switch s {
        case let ready as TodaysDosesUiStateReady:
            let rows = ready.doses.map { d in
                DoseRow(
                    id: "\(d.medicationId):\(d.window.name)",
                    dose: d,
                    name: d.drugName,
                    window: windowLabel(d.window),
                    doseSummary: "\(doseText(d.dose)) \(d.unit)",
                    taken: d.taken,
                )
            }
            return .ready(rows)
        case let error as TodaysDosesUiStateError:
            return .error(error.message)
        default:
            return .loading
        }
    }

    private static func doseText(_ dose: Double) -> String {
        dose == dose.rounded() ? String(Int(dose)) : String(format: "%g", dose)
    }

    private static func windowLabel(_ window: TimeWindow) -> String {
        switch window {
        case SharedCore.TimeWindow.morning:   return "Morning"
        case SharedCore.TimeWindow.afternoon: return "Afternoon"
        case SharedCore.TimeWindow.evening:   return "Evening"
        case SharedCore.TimeWindow.bedtime:   return "Bedtime"
        default: return window.name
        }
    }
}
