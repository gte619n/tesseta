import SwiftUI
import AuthenticationServices
// import SharedCore  // WithingsViewModel, WithingsViewModel.UiState,
//                       WithingsOAuthCoordinator — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A2 — Settings › Devices. Parity target (Android):
/// `feature-settings/.../withings/WithingsSection.kt` +
/// `.../googlehealth/GoogleHealthSection.kt`, driven by the shared
/// `WithingsViewModel`.
///
/// Withings uses a browser authorization-code OAuth flow (NOT Google Sign-In):
/// the shared VM emits an authorize URL on `authorizeRequests`; iOS opens it in an
/// `ASWebAuthenticationSession` with the app's `healthfitness://withings-callback`
/// scheme, then hands the redirect back to the shared
/// `WithingsOAuthCoordinator` (which verifies the CSRF `state` and exchanges the
/// code). This is the exact-analog of Android's Custom Tab + `onNewIntent`
/// redirect relay.
struct DeviceConnectionsView: View {

    /// Local mirror of the shared `WithingsViewModel.UiState`.
    enum WithingsState {
        case loading
        case disconnected(connecting: Bool)
        case connected(connectedAt: Date?, disconnecting: Bool)
        case needsReconnect(reason: String?, reconnecting: Bool)
        case error(String)
    }

    @State private var withings: WithingsState = .loading
    // Holds the ASWebAuthenticationSession for its lifetime (stubbed pre-0D).
    @State private var webAuthSession: ASWebAuthenticationSession?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                withingsCard
                googleHealthCard
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Devices")
        .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(WithingsViewModel(repo: DI.withingsRepository,
        //         coordinator: DI.withingsCoordinator, clientId: DI.withingsClientId))
        //     // Two subscriptions: state → UI, and the one-shot authorize URLs → browser.
        //     async let s: Void = vm.observe(vm.wrapped.state) { self.withings = Self.map($0) }
        //     async let a: Void = vm.observe(vm.wrapped.authorizeRequests) { self.startWebAuth($0) }
        //     _ = await (s, a)
        // }
    }

    // MARK: Withings

    @ViewBuilder
    private var withingsCard: some View {
        SettingsCard(title: "Withings", description: "Body scale + Sleep Analyzer") {
            switch withings {
            case .loading:
                ProgressView()
            case .disconnected(let connecting):
                connectButton(title: "Connect Withings", busy: connecting)
            case .connected(let connectedAt, let disconnecting):
                if let connectedAt {
                    row("Connected", connectedAt.formatted(date: .abbreviated, time: .omitted))
                }
                Button(role: .destructive) {
                    // vm.wrapped.disconnect()
                } label: {
                    if disconnecting { ProgressView() } else { Text("Disconnect") }
                }
                .font(.hfBodyMd)
            case .needsReconnect(let reason, let reconnecting):
                Text(reason ?? "Your Withings connection expired. Please reconnect.")
                    .font(.hfBodySm).foregroundStyle(Theme.warn)
                connectButton(title: "Reconnect Withings", busy: reconnecting)
            case .error(let message):
                Text(message).font(.hfBodySm).foregroundStyle(Theme.alert)
                connectButton(title: "Try again", busy: false)
            }
        }
    }

    private func connectButton(title: String, busy: Bool) -> some View {
        Button {
            // vm.wrapped.connect()  — the shared VM mints the CSRF state, remembers
            // it on the coordinator, and emits the authorize URL on authorizeRequests.
        } label: {
            if busy { ProgressView() } else { Text(title) }
        }
        .font(.hfBodyMd)
        .tint(Theme.accent)
        .disabled(busy)
    }

    // MARK: Google Health (parity placeholder — its own VM in a sibling wave)

    private var googleHealthCard: some View {
        SettingsCard(title: "Google Health", description: "Weight, sleep, and activity") {
            Text("Managed on Android for now.")
                .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
        }
    }

    // MARK: Web-auth session (stubbed until the shared VM is wired at 0D)

    /// Open the Withings authorize URL in an ASWebAuthenticationSession using the
    /// app's `healthfitness://withings-callback` scheme, then relay the redirect
    /// back to the shared coordinator (which the shared VM is already collecting).
    ///
    /// Stubbed here (commented body) because it depends on the shared VM +
    /// coordinator that land at Phase 0D. The session shape is the real one:
    ///
    ///     func startWebAuth(_ authorizeURL: String) {
    ///         guard let url = URL(string: authorizeURL) else { return }
    ///         let session = ASWebAuthenticationSession(
    ///             url: url,
    ///             callbackURLScheme: "healthfitness"   // WithingsOAuthCoordinator.SCHEME
    ///         ) { callbackURL, error in
    ///             // Relay to the shared coordinator; the VM verifies `state` +
    ///             // exchanges the `code`. Mirrors Android's onNewIntent redirect relay.
    ///             DI.withingsCoordinator.handleRedirect(callbackURL, error: error)
    ///         }
    ///         session.presentationContextProvider = self.presentationAnchorProvider
    ///         session.prefersEphemeralWebBrowserSession = false
    ///         session.start()
    ///         self.webAuthSession = session
    ///     }
    private func startWebAuth(_ authorizeURL: String) {
        // Wired at Phase 0D — see the doc comment above for the real body.
    }

    // MARK: Helpers

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(value).font(.hfBodyMd).foregroundStyle(Theme.textTertiary)
        }
    }
}
