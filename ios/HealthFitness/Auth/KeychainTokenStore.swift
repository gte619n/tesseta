import Foundation
import Security

/// Keychain-backed token cache (IMPL-IOS-01 Phase 2A, D6).
///
/// Parity: replaces Android's DataStore token cache. Items are stored with
/// `kSecAttrAccessibleAfterFirstUnlock` **on purpose** (D6): background sync
/// (BGAppRefreshTask / silent-push pull, D7/D8) runs while the device is locked,
/// so the tokens must be readable after the first post-boot unlock. We do NOT
/// use `...ThisDeviceOnly`-WhenUnlocked here because that would starve
/// background sync of credentials.
///
/// Real Security-framework calls — this is not a stub. Access/refresh tokens are
/// stored as two generic-password items under one service.
final class KeychainTokenStore {
    /// Full persisted session (parity with Android's IdTokenCache). Expiries are
    /// epoch seconds, matching the backend `TokenResponse`.
    struct Session {
        let accessToken: String
        let accessTokenExpiresAt: Int64
        let refreshToken: String
        let refreshTokenExpiresAt: Int64
    }

    private let service: String
    private let accessAccount = "accessToken"
    private let accessExpiryAccount = "accessTokenExpiresAt"
    private let refreshAccount = "refreshToken"
    private let refreshExpiryAccount = "refreshTokenExpiresAt"

    init(service: String = "com.gte619n.healthfitness.tokens") {
        self.service = service
    }

    /// True if an access token is present (used by the cached-session launch).
    var hasSession: Bool { accessToken != nil }

    var accessToken: String? { read(account: accessAccount) }
    var refreshToken: String? { read(account: refreshAccount) }

    /// The full cached session, or nil if no access token is stored. Expiries
    /// default to 0 for sessions written before they were tracked.
    var session: Session? {
        guard let accessToken, let refreshToken else { return nil }
        return Session(
            accessToken: accessToken,
            accessTokenExpiresAt: readInt(account: accessExpiryAccount),
            refreshToken: refreshToken,
            refreshTokenExpiresAt: readInt(account: refreshExpiryAccount))
    }

    func save(
        accessToken: String,
        accessTokenExpiresAt: Int64,
        refreshToken: String,
        refreshTokenExpiresAt: Int64
    ) {
        write(account: accessAccount, value: accessToken)
        write(account: accessExpiryAccount, value: String(accessTokenExpiresAt))
        write(account: refreshAccount, value: refreshToken)
        write(account: refreshExpiryAccount, value: String(refreshTokenExpiresAt))
    }

    func clear() {
        delete(account: accessAccount)
        delete(account: accessExpiryAccount)
        delete(account: refreshAccount)
        delete(account: refreshExpiryAccount)
    }

    private func readInt(account: String) -> Int64 {
        read(account: account).flatMap(Int64.init) ?? 0
    }

    // MARK: - Security framework wrappers

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func read(account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else {
            return nil
        }
        return value
    }

    private func write(account: String, value: String) {
        let data = Data(value.utf8)
        // Upsert: delete any existing item, then add with the D6 accessibility.
        delete(account: account)
        var query = baseQuery(account: account)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    private func delete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }
}
