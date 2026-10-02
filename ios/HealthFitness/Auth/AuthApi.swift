import Foundation

/// Backend session-token endpoints (ADR-0010), 1:1 with Android's `AuthApi`.
///
/// A DEDICATED client with NO bearer interceptor and NO 401-refresh recursion:
///   - `exchange` supplies the Google ID token explicitly in the Authorization
///     header and must not also attach a (not-yet-existing) session bearer.
///   - `refresh` / `logout` carry an opaque refresh token in the body and are
///     public on the backend (SecurityConfig), so they take no bearer either.
///
/// `TokenResponse` matches the backend record exactly (4 fields incl. expiries);
/// the Android DTO drops nothing, so neither do we.
struct AuthApi {
    struct TokenResponse: Decodable, Sendable {
        let accessToken: String
        let accessTokenExpiresAt: Int64
        let refreshToken: String
        let refreshTokenExpiresAt: Int64
    }

    enum AuthError: Error, LocalizedError {
        /// The refresh token is dead (revoked/expired) — caller must sign in
        /// interactively. Mirrors Android's HttpException(401) branch.
        case refreshExpired
        /// Non-2xx that isn't a 401-on-refresh.
        case http(status: Int, body: String)
        /// Session tokens not configured on the backend (503) — surfaces the
        /// same "not configured" condition Android's requireEnabled() guards.
        case notConfigured
        case transport(Error)

        var errorDescription: String? {
            switch self {
            case .refreshExpired: "Your session expired. Please sign in again."
            case .http(let status, _): "Server error (HTTP \(status))."
            case .notConfigured: "Sign-in is temporarily unavailable."
            case .transport(let e): "Network error: \(e.localizedDescription)"
            }
        }
    }

    private let baseURL: URL
    private let session: URLSession

    init(baseURL: URL = AppConfig.backendBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// Trade a freshly-obtained Google ID token (passed explicitly in the header)
    /// for the first access + refresh pair. Empty body — the backend reads the
    /// identity from the validated Google JWT, not the request body.
    func exchange(googleIDToken: String) async throws -> TokenResponse {
        var request = makeRequest(path: "api/auth/exchange")
        request.setValue("Bearer \(googleIDToken)", forHTTPHeaderField: "Authorization")
        return try await send(request)
    }

    /// Silent, UI-free: trade a refresh token for a new pair. Throws
    /// `.refreshExpired` on 401 so the caller falls back to interactive sign-in.
    func refresh(refreshToken: String) async throws -> TokenResponse {
        var request = makeRequest(path: "api/auth/refresh")
        request.httpBody = try JSONEncoder().encode(RefreshRequest(refreshToken: refreshToken))
        return try await send(request)
    }

    /// Best-effort server-side revocation. Never throws on a non-2xx — logout
    /// must not block the local wipe (parity with Android's swallow).
    func logout(refreshToken: String) async {
        var request = makeRequest(path: "api/auth/logout")
        request.httpBody = try? JSONEncoder().encode(RefreshRequest(refreshToken: refreshToken))
        _ = try? await session.data(for: request)
    }

    /// UAT / local-only bootstrap: mint a real session with no Google sign-in.
    /// 404s on prod (backend dev-login guard). Parity with the web UAT sign-in.
    func devLogin(userId: String?, email: String?, name: String?) async throws -> TokenResponse {
        var request = makeRequest(path: "api/auth/dev-login")
        request.httpBody = try JSONEncoder().encode(
            DevLoginRequest(userId: userId, email: email, name: name))
        return try await send(request)
    }

    // MARK: - Internals

    private func makeRequest(path: String) -> URLRequest {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("ios", forHTTPHeaderField: "X-Client")
        return request
    }

    private func send(_ request: URLRequest) async throws -> TokenResponse {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw AuthError.transport(error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw AuthError.http(status: -1, body: "")
        }
        switch http.statusCode {
        case 200...299:
            return try JSONDecoder().decode(TokenResponse.self, from: data)
        case 401:
            // Only /refresh can 401 here (exchange 401s mean a bad Google token,
            // which the caller also treats as "sign in again").
            throw AuthError.refreshExpired
        case 503:
            throw AuthError.notConfigured
        default:
            throw AuthError.http(
                status: http.statusCode,
                body: String(data: data, encoding: .utf8) ?? "")
        }
    }

    private struct RefreshRequest: Encodable { let refreshToken: String }
    private struct DevLoginRequest: Encodable {
        let userId: String?
        let email: String?
        let name: String?
    }
}
