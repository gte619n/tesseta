import Foundation
import Testing
@testable import HealthFitness

/// IMPL-IOS-01 Phase 2B — the presentation-only JWT claim decode used to show
/// the signed-in account without a network call. Pure Swift (no SharedCore, no
/// network), so this runs today. Parity with Android's `decodeClaims`:
/// base64url payload, URL-safe alphabet, tolerant of missing padding.
@Suite("Auth token claims")
struct AuthTokenClaimsTests {

    /// Build an unsigned JWT with the given payload JSON. Only the middle
    /// segment is read by the decoder, so header/signature are placeholders.
    private func makeJWT(payloadJSON: String) -> String {
        func b64url(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")  // strip padding (the real case)
        }
        return "\(b64url("{\"alg\":\"HS256\"}")).\(b64url(payloadJSON)).sig"
    }

    @Test("decodes email + name from a well-formed payload (padding stripped)")
    func decodesIdentity() {
        let jwt = makeJWT(payloadJSON: #"{"sub":"u1","email":"a@b.com","name":"Ada"}"#)
        let claims = AuthState.decodeJWTClaims(jwt)
        #expect(claims["email"] as? String == "a@b.com")
        #expect(claims["name"] as? String == "Ada")
        #expect(claims["sub"] as? String == "u1")
    }

    @Test("malformed tokens decode to empty claims, never crash")
    func malformedIsEmpty() {
        #expect(AuthState.decodeJWTClaims("").isEmpty)
        #expect(AuthState.decodeJWTClaims("not-a-jwt").isEmpty)
        #expect(AuthState.decodeJWTClaims("only.two").isEmpty)  // middle segment isn't base64 JSON
    }
}
