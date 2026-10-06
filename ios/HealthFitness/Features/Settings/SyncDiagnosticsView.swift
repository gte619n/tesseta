import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave A2 — Settings › Sync log. Parity target (Android):
/// `app/.../mobile/sync/SyncLogScreen.kt` + `SyncStatusViewModel` (the global
/// sync signals: online, queued/failed outbox rows, last error, "updated
/// elsewhere"). Read-first: the view renders the current sync state and offers
/// Retry (re-arm + drain the outbox) and a foreground Refresh (delta pull).
///
/// The concrete sync engine + outbox live in the shared sync layer (Phase 1C);
/// this view binds to their combined `SyncUiState` at 0D. Rendered here from a
/// local mirror so the surface is complete and reviewable now.
struct SyncDiagnosticsView: View {

    /// Local mirror of the shared `SyncUiState` (online + counts + last error).
    struct SyncStatus {
        var online: Bool
        var pendingCount: Int
        var failedCount: Int
        var updatedElsewhere: Bool
        var lastError: String?
    }

    @State private var status = SyncStatus(
        online: true, pendingCount: 0, failedCount: 0, updatedElsewhere: false, lastError: nil
    )

    private let vm = IosComposition.shared.syncStatusViewModel()
    @State private var subscription: FlowSubscription?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                statusCard
                if status.failedCount > 0 || status.pendingCount > 0 {
                    actionsCard
                }
                if let lastError = status.lastError {
                    errorCard(lastError)
                }
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Sync log")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { vm.refresh() }
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                if let s = value as? SyncStatusViewModel.UiState { status = Self.map(s) }
            }
        }
        .onDisappear { subscription?.cancel() }
    }

    static func map(_ s: SyncStatusViewModel.UiState) -> SyncStatus {
        SyncStatus(
            online: true,  // no shared reachability source yet
            pendingCount: Int(s.pendingCount),
            failedCount: Int(s.failedCount),
            updatedElsewhere: false,
            lastError: s.lastError,
        )
    }

    private var statusCard: some View {
        SettingsCard(title: "Status") {
            statusRow("Network", status.online ? "Online" : "Offline",
                      tint: status.online ? Theme.good : Theme.warn)
            statusRow("Queued changes", "\(status.pendingCount)",
                      tint: status.pendingCount == 0 ? Theme.textTertiary : Theme.neutral)
            statusRow("Failed changes", "\(status.failedCount)",
                      tint: status.failedCount == 0 ? Theme.textTertiary : Theme.alert)
            if status.updatedElsewhere {
                Text("Updated on another device — pull to refresh.")
                    .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private var actionsCard: some View {
        SettingsCard(title: "Actions",
                     description: "Re-send anything that hasn’t reached the server") {
            Button {
                vm.retry()  // re-arm FAILED rows + drain the outbox
            } label: {
                Text(status.failedCount > 0 ? "Retry failed changes" : "Sync now")
            }
            .font(.hfBodyMd)
            .tint(Theme.accent)
        }
    }

    private func errorCard(_ message: String) -> some View {
        SettingsCard(title: "Last error") {
            Text(message).font(.hfMonoSm).foregroundStyle(Theme.alert)
        }
    }

    private func statusRow(_ label: String, _ value: String, tint: Color) -> some View {
        HStack {
            Text(label).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(value).font(.hfMonoSm).foregroundStyle(tint)
        }
    }

    // static func map(_ s: SyncUiState) -> SyncStatus { ... }  // Phase 0D
}
