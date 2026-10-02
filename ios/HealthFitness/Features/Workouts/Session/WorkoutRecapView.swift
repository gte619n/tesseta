import SwiftUI

/// IMPL-IOS-01 Phase 3 Wave D(ii) — the post-workout recap, shown over the
/// retained draft snapshot after `confirmFinish` succeeds (parity with Android's
/// finish recap). Renders the session tally + the best-effort AI coach note; the
/// note area shows a spinner while `recapLoading`, and the summary stands on its
/// own if the note never arrives (the recap fetch never blocks finishing).
struct WorkoutRecapView: View {

    let dayLabel: String
    let completedSets: Int
    let totalSets: Int
    let elapsed: TimeInterval
    let recap: String?
    let recapLoading: Bool
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 52)).foregroundStyle(Theme.good)
                        .padding(.top, 24)
                    Text("Workout complete").font(.hfDisplayMd).foregroundStyle(Theme.textPrimary)
                    Text(dayLabel).font(.hfBodyMd).foregroundStyle(Theme.textSecondary)

                    HStack(spacing: 24) {
                        stat(value: "\(completedSets)/\(totalSets)", label: "Sets")
                        stat(value: formatDuration(elapsed), label: "Time")
                    }
                    .padding().frame(maxWidth: .infinity)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))

                    coachNote

                    Spacer(minLength: 12)
                    Button(action: onDone) {
                        Text("Done").frame(maxWidth: .infinity).padding()
                    }.buttonStyle(.borderedProminent).tint(Theme.accent)
                }
                .padding()
                .formMaxWidth()
            }
            .background(Theme.canvas)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    @ViewBuilder
    private var coachNote: some View {
        if recapLoading {
            HStack(spacing: 8) {
                ProgressView()
                Text("Coach is reviewing your session…")
                    .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            }.padding()
        } else if let recap, !recap.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Coach note").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Text(recap).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func stat(value: String, label: String) -> some View {
        VStack(spacing: 4) {
            Text(value).font(.hfHeadingLg).foregroundStyle(Theme.textPrimary)
            Text(label).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
        }.frame(maxWidth: .infinity)
    }

    private func formatDuration(_ seconds: TimeInterval) -> String {
        let total = Int(seconds)
        let h = total / 3600, m = (total % 3600) / 60
        return h > 0 ? "\(h)h \(m)m" : "\(m)m"
    }
}
