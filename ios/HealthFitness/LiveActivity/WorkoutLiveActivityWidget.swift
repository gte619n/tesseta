import SwiftUI
import WidgetKit
import ActivityKit

/// IMPL-IOS-01 Phase 3 Wave D(ii) — the lock-screen + Dynamic Island rendering of
/// the active-workout Live Activity. This is the widget-extension target's view;
/// it renders `WorkoutActivityAttributes.ContentState` (a pure projection of the
/// shared session VM). Both surfaces show the day label, current exercise, and
/// either the elapsed clock or the rest countdown — self-ticking via
/// `Text(timerInterval:)` so no per-second push is needed.
///
/// NOTE: this file belongs to the WIDGET EXTENSION target (a separate bundle from
/// the app). The `@main WidgetBundle` that registers it lives in the extension's
/// entry point, which the integrator adds when the extension target is created;
/// documented at the bottom.
struct WorkoutLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WorkoutActivityAttributes.self) { context in
            // Lock screen / banner presentation.
            WorkoutLockScreenView(attributes: context.attributes, state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.85))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            dynamicIsland(context)
        }
    }

    private func dynamicIsland(
        _ context: ActivityViewContext<WorkoutActivityAttributes>
    ) -> DynamicIsland {
        let state = context.state
        return DynamicIsland {
            // Expanded presentation.
            DynamicIslandExpandedRegion(.leading) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.dayLabel)
                        .font(.caption2).foregroundStyle(.secondary)
                    Text(state.currentExercise)
                        .font(.headline).lineLimit(1)
                }
            }
            DynamicIslandExpandedRegion(.trailing) {
                countdownOrElapsed(context, style: .headline)
                    .multilineTextAlignment(.trailing)
            }
            DynamicIslandExpandedRegion(.bottom) {
                HStack {
                    Text(state.setProgress).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    ProgressView(value: state.progress)
                        .tint(.green)
                        .frame(width: 90)
                }
            }
        } compactLeading: {
            Image(systemName: state.isComplete ? "checkmark.circle.fill" : "dumbbell.fill")
                .foregroundStyle(state.isComplete ? .green : .primary)
        } compactTrailing: {
            countdownOrElapsed(context, style: .caption2)
        } minimal: {
            Image(systemName: state.isResting ? "timer" : "dumbbell.fill")
                .foregroundStyle(state.isResting ? .orange : .primary)
        }
    }

    /// Rest countdown when resting, otherwise elapsed time — both self-tick.
    @ViewBuilder
    private func countdownOrElapsed(
        _ context: ActivityViewContext<WorkoutActivityAttributes>,
        style: Font
    ) -> some View {
        if let restEndsAt = context.state.restEndsAt {
            Text(timerInterval: Date.now...restEndsAt, countsDown: true)
                .font(style).monospacedDigit()
                .foregroundStyle(context.state.restIsGetReady ? .yellow : .orange)
        } else {
            Text(context.attributes.startedAt, style: .timer)
                .font(style).monospacedDigit()
        }
    }
}

/// The lock-screen / banner card.
struct WorkoutLockScreenView: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().stroke(.white.opacity(0.2), lineWidth: 5)
                Circle()
                    .trim(from: 0, to: state.progress)
                    .stroke(.green, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: state.isComplete ? "checkmark" : "dumbbell.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 3) {
                Text(attributes.dayLabel)
                    .font(.caption2).foregroundStyle(.white.opacity(0.6))
                Text(state.currentExercise)
                    .font(.headline).foregroundStyle(.white).lineLimit(1)
                Text(state.setProgress)
                    .font(.caption).foregroundStyle(.white.opacity(0.7))
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                if let restEndsAt = state.restEndsAt {
                    Text(state.restIsGetReady ? "Get ready" : "Rest")
                        .font(.caption2).foregroundStyle(.white.opacity(0.6))
                    Text(timerInterval: Date.now...restEndsAt, countsDown: true)
                        .font(.title2).monospacedDigit()
                        .foregroundStyle(state.restIsGetReady ? .yellow : .orange)
                } else {
                    Text("Elapsed")
                        .font(.caption2).foregroundStyle(.white.opacity(0.6))
                    Text(attributes.startedAt, style: .timer)
                        .font(.title2).monospacedDigit()
                        .foregroundStyle(.white)
                }
            }
        }
        .padding(16)
    }
}

// INTEGRATOR NOTE — Live Activities require a Widget Extension target. Create it
// (File ▸ New ▸ Target ▸ Widget Extension, "Include Live Activity" checked), add
// these LiveActivity/*.swift files to that target's membership (plus the shared
// `WorkoutActivityAttributes.swift`, which must be in BOTH the app and the
// extension), and register the widget in the extension's bundle entry point:
//
//     @main
//     struct WorkoutWidgetBundle: WidgetBundle {
//         var body: some Widget { WorkoutLiveActivityWidget() }
//     }
//
// Also add `NSSupportsLiveActivities = YES` to the APP target's Info.plist.
