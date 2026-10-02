import SwiftUI

/// SwiftUI equivalents of the Android `core-ui` settings/form primitives
/// (SettingsCard / NavRow / ToggleRow / SegmentedChoice). Same title/description
/// typography, same control vocabulary — so settings and form screens on both
/// clients read identically.
///
/// Android source: android/core-ui/.../components/SettingsComponents.kt.

// MARK: - SettingsCard

/// A titled card: `headingSm` title, optional `bodySm` description, and content
/// laid out in a column with the canonical 14pt padding/spacing.
struct SettingsCard<Content: View>: View {
    let title: String
    var description: String?
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.hfHeadingSm)
                    .foregroundStyle(Theme.textPrimary)
                if let description {
                    Text(description)
                        .font(.hfBodySm)
                        .foregroundStyle(Theme.textTertiary)
                }
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(Theme.borderDefault, lineWidth: 0.5)
        )
    }
}

// MARK: - NavRow

/// Navigation row: label (+ optional subtitle) with a trailing chevron.
struct NavRow: View {
    let label: String
    var subtitle: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.hfBodyMd)
                        .foregroundStyle(Theme.textPrimary)
                    if let subtitle {
                        Text(subtitle)
                            .font(.hfBodySm)
                            .foregroundStyle(Theme.textTertiary)
                    }
                }
                Spacer(minLength: 12)
                Image(systemName: "chevron.right")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textTertiary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - ToggleRow

/// On/off preference row with the app-standard tint.
struct ToggleRow: View {
    let label: String
    var description: String?
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textPrimary)
                if let description {
                    Text(description)
                        .font(.hfBodySm)
                        .foregroundStyle(Theme.textTertiary)
                }
            }
        }
        .tint(Theme.accent)
    }
}

// MARK: - SegmentedChoice

/// Compact segmented control for small closed choices (units, biological sex).
/// Generic over the value type; each option is a (value, label) pair.
struct SegmentedChoice<T: Hashable>: View {
    let options: [(value: T, label: String)]
    @Binding var selection: T?
    var isEnabled: Bool = true

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.value) { option in
                let selected = option.value == selection
                Text(option.label)
                    .font(.hfCapsSm)
                    .foregroundStyle(selected ? Theme.textInverse : Theme.textSecondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(selected ? Theme.accent : .clear,
                                in: RoundedRectangle(cornerRadius: 7))
                    .contentShape(Rectangle())
                    .onTapGesture { if isEnabled { selection = option.value } }
            }
        }
        .padding(2)
        .overlay(
            RoundedRectangle(cornerRadius: 9)
                .strokeBorder(Theme.borderStrong, lineWidth: 0.5)
        )
        .opacity(isEnabled ? 1 : 0.5)
    }
}

// MARK: - Preview

#Preview {
    ScrollView {
        VStack(spacing: 16) {
            SettingsCard(title: "Units", description: "How measurements are shown") {
                SegmentedChoicePreview()
                ToggleRow(label: "Use metric", description: "kg, cm, °C", isOn: .constant(true))
                NavRow(label: "Advanced", subtitle: "Sync & diagnostics") {}
            }
        }
        .padding()
        .formMaxWidth()
    }
    .background(Theme.canvas)
}

private struct SegmentedChoicePreview: View {
    @State private var value: String? = "imperial"
    var body: some View {
        SegmentedChoice(
            options: [("metric", "Metric"), ("imperial", "Imperial")],
            selection: $value
        )
    }
}
