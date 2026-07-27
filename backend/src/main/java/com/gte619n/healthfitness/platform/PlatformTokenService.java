package com.gte619n.healthfitness.platform;

import com.gte619n.healthfitness.core.platform.PlatformRefreshToken;
import com.gte619n.healthfitness.core.platform.PlatformRefreshTokenStore;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Mints and rotates the third-party token family (ADR-0020).
//
// Access tokens are short-lived RS256 JWTs (validated statelessly against the
// published JWKS) carrying `client_id` and `scope` so the resource server can
// enforce delegated authority. Refresh tokens are opaque, stored only as a hash,
// and rotated with the same successor-chain benign-replay recovery as the
// first-party session tokens (ADR-0019) — a rotated token keeps its clientId and
// scopes, so refreshing preserves exactly the grant the user consented to.
//
// Only issued/rotated when the grant includes offline_access; otherwise the
// caller gets an access token alone and must re-authorize when it expires.
//
// Gated on app.platform.enabled: it depends on PlatformKeys (also gated), so it
// stays dormant — with the rest of the platform beans — when the feature is off.
@Service
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformTokenService {

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    private static final int SECRET_BYTES = 32;
    private static final int MAX_CHAIN_HOPS = 64;

    private final AppPlatformProperties props;
    private final PlatformKeys keys;
    private final PlatformRefreshTokenStore store;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public PlatformTokenService(
        AppPlatformProperties props,
        PlatformKeys keys,
        PlatformRefreshTokenStore store,
        UserRepository users
    ) {
        this.props = props;
        this.keys = keys;
        this.store = store;
        this.users = users;
    }

    // Fresh grant (authorization_code): mint an access token and, iff
    // offline_access was granted, a refresh token bound to this user+client+scopes.
    public IssuedTokens issue(String userId, String email, String name,
                              String clientId, Set<String> scopes) {
        Instant now = Instant.now();
        String access = mintAccessToken(userId, email, name, clientId, scopes, now);
        String refresh = scopes.contains(PlatformScope.OFFLINE_ACCESS.wire())
            ? issueRefreshToken(userId, clientId, scopes, now)
            : null;
        return new IssuedTokens(access, props.getAccessTtl().getSeconds(), refresh, scopes);
    }

    // refresh_token grant: rotate the presented token and mint a new access
    // token for the same grant. `clientId` is the authenticated caller — a
    // refresh token minted for one client can never be redeemed by another.
    public IssuedTokens refresh(String refreshToken, String clientId) {
        Parsed parsed = parse(refreshToken);
        PlatformRefreshToken stored = store.findById(parsed.tokenId())
            .orElseThrow(() -> new InvalidRefreshTokenException("unknown refresh token"));
        if (!constantTimeEquals(stored.tokenHash(), sha256(parsed.secret()))) {
            throw new InvalidRefreshTokenException("refresh token secret mismatch");
        }
        if (!stored.clientId().equals(clientId)) {
            throw new InvalidRefreshTokenException("refresh token was issued to another client");
        }
        Instant now = Instant.now();
        if (!stored.revoked() && stored.isExpired(now)) {
            throw new InvalidRefreshTokenException("refresh token expired");
        }
        return advanceChain(stored, now);
    }

    public void revoke(String refreshToken) {
        try {
            Parsed parsed = parse(refreshToken);
            store.findById(parsed.tokenId())
                .filter(s -> constantTimeEquals(s.tokenHash(), sha256(parsed.secret())))
                .ifPresent(s -> store.markRevoked(s.tokenId()));
        } catch (InvalidRefreshTokenException ignored) {
            // already gone — nothing to revoke
        }
    }

    // --- Successor-chain rotation (mirrors SessionTokenService, ADR-0019). ---

    private IssuedTokens advanceChain(PlatformRefreshToken presented, Instant now) {
        PlatformRefreshToken tip = liveTip(presented, now);
        if (tip == null) {
            store.revokeAllForUser(presented.userId());
            throw new InvalidRefreshTokenException("refresh token already used");
        }

        String successorId = UUID.randomUUID().toString();
        String secret = randomSecret();

        if (!store.tryMarkRotated(tip.tokenId(), now, successorId)) {
            return advanceChain(reRead(presented), now);
        }

        store.save(new PlatformRefreshToken(
            successorId, tip.userId(), tip.clientId(), tip.scopes(), sha256(secret),
            now, now.plus(props.getRefreshTtl()), false, null, null));
        if (!presented.tokenId().equals(tip.tokenId())) {
            store.repoint(presented.tokenId(), successorId);
        }

        User user = users.findById(tip.userId())
            .orElseThrow(() -> new InvalidRefreshTokenException("user no longer exists"));
        String access = mintAccessToken(
            user.userId(), user.email(), user.displayName(), tip.clientId(), tip.scopes(), now);
        return new IssuedTokens(
            access, props.getAccessTtl().getSeconds(), successorId + "." + secret, tip.scopes());
    }

    private PlatformRefreshToken liveTip(PlatformRefreshToken presented, Instant now) {
        PlatformRefreshToken cur = presented;
        for (int hop = 0; hop < MAX_CHAIN_HOPS; hop++) {
            if (!cur.revoked()) {
                return cur.isExpired(now) ? null : cur;
            }
            String next = cur.replacedBy();
            if (next == null) {
                return null; // logout / theft burn
            }
            PlatformRefreshToken succ = store.findById(next).orElse(null);
            if (succ == null) {
                return null; // dangling successor
            }
            cur = succ;
        }
        return null; // corrupt chain
    }

    private PlatformRefreshToken reRead(PlatformRefreshToken presented) {
        return store.findById(presented.tokenId()).orElse(presented);
    }

    // --- Minting. ---

    private String mintAccessToken(String userId, String email, String name,
                                   String clientId, Set<String> scopes, Instant now) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(props.getIssuer())
            .audience(props.getAudience())
            .subject(userId)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(props.getAccessTtl())))
            .claim("client_id", clientId)
            // Space-delimited per RFC 8693; Spring's default converter maps this
            // to SCOPE_* authorities for @PreAuthorize on the /v1 resource API.
            .claim("scope", PlatformScope.join(scopes));
        if (email != null && scopes.contains(PlatformScope.PROFILE_READ.wire())) {
            claims.claim("email", email);
        }
        if (name != null && scopes.contains(PlatformScope.PROFILE_READ.wire())) {
            claims.claim("name", name);
        }
        try {
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.keyId()).build(),
                claims.build());
            jwt.sign(keys.signer());
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign platform access token", e);
        }
    }

    private String issueRefreshToken(String userId, String clientId, Set<String> scopes, Instant now) {
        String tokenId = UUID.randomUUID().toString();
        String secret = randomSecret();
        store.save(new PlatformRefreshToken(
            tokenId, userId, clientId, scopes, sha256(secret),
            now, now.plus(props.getRefreshTtl()), false, null, null));
        return tokenId + "." + secret;
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Parsed parse(String refreshToken) {
        if (refreshToken == null) {
            throw new InvalidRefreshTokenException("missing refresh token");
        }
        int dot = refreshToken.indexOf('.');
        if (dot <= 0 || dot == refreshToken.length() - 1) {
            throw new InvalidRefreshTokenException("malformed refresh token");
        }
        return new Parsed(refreshToken.substring(0, dot), refreshToken.substring(dot + 1));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private record Parsed(String tokenId, String secret) {}
}
