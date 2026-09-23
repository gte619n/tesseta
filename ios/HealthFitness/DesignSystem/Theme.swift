import SwiftUI

/// Design tokens mirrored 1:1 from the Android `core-ui` theme
/// (`HfColors` / `HfTypography`). Keeping the palette identical is what makes
/// the two clients feel like one product; treat this as the SSOT on iOS.
///
/// Android source: android/core-ui/.../theme/Colors.kt, Typography.kt.
enum Theme {
    // MARK: Colors (from HfColors — light "paper" palette)
    static let canvas = Color(hex: 0xF0EBE0)
    static let canvasMuted = Color(hex: 0xEBE4D0)
    static let canvasSunken = Color(hex: 0xF0EBE0)
    static let surface = Color(hex: 0xFFFFFF)

    static let borderSubtle = Color(hex: 0xEFE7D2)
    static let borderDefault = Color(hex: 0xE6DFCF)
    static let borderStrong = Color(hex: 0xDDD3BB)

    static let textPrimary = Color(hex: 0x1F2419)
    static let textSecondary = Color(hex: 0x6B6856)
    static let textTertiary = Color(hex: 0x8A8770)
    static let textQuaternary = Color(hex: 0xB5B09C)
    static let textInverse = Color(hex: 0xF0EBE0)

    static let accent = Color(hex: 0x5C7A2E)
    static let accentDim = Color(hex: 0x3D5A1E)
    static let accentBg = Color(hex: 0xE8EBD8)

    static let good = Color(hex: 0x5C7A2E)
    static let goodBg = Color(hex: 0xE8EBD8)
    static let warn = Color(hex: 0xA06A1F)
    static let warnBg = Color(hex: 0xFBE9DA)
    static let alert = Color(hex: 0xA8473A)
    static let alertBg = Color(hex: 0xF7E1DC)
    static let neutral = Color(hex: 0x3B6B8E)
    static let muted = Color(hex: 0xB5B09C)

    /// Max width for settings/form content columns — Android's
    /// `SettingsContentMaxWidth = 600.dp`. Wider windows center the column
    /// rather than stretching cards edge-to-edge (see `.formMaxWidth()`).
    static let formMaxWidth: CGFloat = 600
}

/// Typography mirrored from `HfTypography`. Android uses Instrument Sans (body)
/// and JetBrains Mono (numerics/display). On iOS we map to the same families by
/// name when the fonts are bundled; the sizes/weights track the Android scale.
/// (Font bundling is a Phase 3 polish item; `.monospaced` design is used as a
/// faithful fallback for the mono/numeric styles until then.)
extension Font {
    // Mono numerics / display
    static let hfDisplayXl = Font.system(size: 36, weight: .medium).monospaced()
    static let hfDisplayLg = Font.system(size: 28, weight: .medium).monospaced()
    static let hfDisplayMd = Font.system(size: 22, weight: .medium).monospaced()
    static let hfMonoSm = Font.system(size: 13, weight: .regular).monospaced()

    // Sans body / headings
    static let hfHeadingLg = Font.system(size: 20, weight: .medium)
    static let hfHeadingSm = Font.system(size: 15, weight: .medium)
    static let hfBodyLg = Font.system(size: 17, weight: .regular)
    static let hfBodyMd = Font.system(size: 15, weight: .regular)
    static let hfBodySm = Font.system(size: 13, weight: .regular)
    static let hfCapsSm = Font.system(size: 11, weight: .medium)
}

extension Color {
    /// Build a Color from a 0xRRGGBB integer (parity with Compose `Color(0x..)`).
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: 1)
    }
}

// MARK: - Form max-width modifier

private struct FormMaxWidth: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: Theme.formMaxWidth)
            .frame(maxWidth: .infinity)   // center within wider windows
    }
}

extension View {
    /// Constrain content to the 600pt form width and center it in wider windows
    /// — parity with Android's `SettingsContentMaxWidth` handling (D16).
    func formMaxWidth() -> some View {
        modifier(FormMaxWidth())
    }
}
