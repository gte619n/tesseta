import Foundation
import SharedCore

/// Bridges the Keychain session token (written by login) to the shared Ktor REST
/// client (IMPL-IOS-01 Phase 1C). Conforms to the KMP `SessionTokenProvider` so
/// `ApiClient` can attach `Authorization: Bearer <token>` on every request; the
/// token is read per-call, so a silent refresh is picked up without rebuilding
/// the client. NSObject subclass for Kotlin/Native ObjC-protocol conformance.
final class KeychainTokenProvider: NSObject, SessionTokenProvider {
    private let store: KeychainTokenStore

    init(store: KeychainTokenStore) {
        self.store = store
    }

    func currentAccessToken() -> String? {
        store.accessToken
    }
}
