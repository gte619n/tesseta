import SwiftUI
import SharedCore

/// Medications list (IMPL-IOS-01 Phase 1C — networked shared screen).
///
/// Backed by the shared `MedicationsViewModel` over the existing
/// `GET /api/me/medications` endpoint (the same the Android app uses). Renders
/// the sealed `MedicationsUiState` from `collectFlow`: active meds in the main
/// list, discontinued under a History section.
struct MedicationsListView: View {
    private let vm: MedicationsViewModel
    @State private var state: MedicationsUiState
    @State private var subscription: FlowSubscription?

    init() {
        let model = IosComposition.shared.medicationsViewModel()
        self.vm = model
        _state = State(initialValue: model.state.value as! MedicationsUiState)
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.canvas)
            .navigationTitle("Medications")
            .accessibilityIdentifier("medications-list")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? MedicationsUiState { state = s }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder private var content: some View {
        switch state {
        case let ready as MedicationsUiStateReady:
            list(active: ready.active, discontinued: ready.discontinued)
        case let error as MedicationsUiStateError:
            message("Couldn't load medications", error.message, retry: true)
        default:  // Loading
            ProgressView().controlSize(.large).tint(Theme.accent)
        }
    }

    @ViewBuilder
    private func list(active: [Medication], discontinued: [Medication]) -> some View {
        if active.isEmpty && discontinued.isEmpty {
            message("No medications", "Add a medication to start tracking it.", retry: false)
        } else {
            ScrollView {
                VStack(spacing: 16) {
                    if !active.isEmpty {
                        SettingsCard(title: "Current") {
                            ForEach(active, id: \.medicationId) { row($0) }
                        }
                    }
                    if !discontinued.isEmpty {
                        SettingsCard(title: "History") {
                            ForEach(discontinued, id: \.medicationId) { row($0) }
                        }
                    }
                }
                .formMaxWidth()
                .padding(.vertical, 16)
            }
        }
    }

    private func row(_ med: Medication) -> some View {
        HStack {
            Text(med.customName ?? med.drug?.name ?? "Medication")
                .font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            Spacer()
            Text("\(doseText(med.dose)) \(med.unit)")
                .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
        }
        .padding(.vertical, 6)
    }

    private func doseText(_ dose: Double) -> String {
        dose == dose.rounded() ? String(Int(dose)) : String(format: "%g", dose)
    }

    private func message(_ title: String, _ body: String, retry: Bool) -> some View {
        VStack(spacing: 16) {
            Text(title).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            Text(body).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            if retry {
                Button("Retry") { vm.refresh() }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
            }
        }
        .padding(32)
    }
}
