package com.gte619n.healthfitness.auth;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import com.gte619n.healthfitness.core.auth.RefreshTokenStore.StoredRefreshToken;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Issues and refreshes the backend's own session tokens for native clients
// (ADR-0010). Access tokens are short-lived HS256 JWTs (so the resource server
// validates them statelessly, exactly like a Google ID token); refresh tokens
// are opaque secrets persisted only as a hash, rotated on every use.
//
// The access token carries the same identity claims a Google ID token would
// (`sub`/`email`/`name`) so CurrentUser resolution downstream is unchanged. We
// deliberately omit `picture`: Google hosts that image and the URL drifts, so
// like the rest of the system we never persist it — photoUrl is simply null
// for session-token requests.
@Service
public class SessionTokenService {

    // Returned to the caller: the bearer to send now, plus the refresh token to
    // store for later. Expiries are epoch-seconds so clients can pre-empt the
    // refresh without re-parsing the JWT.
    public record TokenPair(
        String accessToken,
        long accessTokenExpiresAt,
        String refreshToken,
        long refreshTokenExpiresAt
    ) {}

    // Thrown when a refresh token is unknown, expired, revoked, or malformed.
    // The controller maps it to 401 so the client falls back to interactive
    // sign-in.
    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    private static final int SECRET_BYTES = 32; // 256-bit refresh secret

    private final AppSessionProperties props;
    private final RefreshTokenStore store;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public SessionTokenService(
        AppSessionProperties props,
        RefreshTokenStore store,
        UserRepository users
    ) {
        this.props = props;
        this.store = store;
        this.users = users;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    // First leg: a freshly-validated Google identity is exchanged for a session.
    public TokenPair issueFor(CurrentUser user) {
        Instant now = Instant.now();
        String access = mintAccessToken(user.userId(), user.email(), user.displayName(), now);
        return new TokenPair(
            access,
            now.plus(props.getAccessTtl()).getEpochSecond(),
            issueRefreshToken(user.userId(), now),
            now.plus(props.getRefreshTtl()).getEpochSecond()
        );
    }

    // Second leg: a stored refresh token buys a new access token and a rotated
    // refresh token. Rotation is single-use, but a *replay* of an already-rotated
    // token is not automatically theft — on mobile it's usually a benign retry of
    // a refresh whose response was lost in flight, so the client still holds the
    // old token. We resolve the token's successor chain to its live tip and
    // advance that (ADR-0019), so the retry succeeds however long the phone was
    // offline. Only a token revoked with no successor (logout / a prior theft
    // burn) or a chain whose tip is dead burns the family.
    public TokenPair refresh(String refreshToken) {
        Parsed parsed = parse(refreshToken);
        StoredRefreshToken stored = store.findById(parsed.tokenId())
            .orElseThrow(() -> new InvalidRefreshTokenException("unknown refresh token"));

        if (!constantTimeEquals(stored.tokenHash(), sha256(parsed.secret()))) {
            throw new InvalidRefreshTokenException("refresh token secret mismatch");
        }
        Instant now = Instant.now();
        // A live token that is simply past its expiry is rejected outright (never
        // recoverable). A *revoked* token falls through to the chain walk, which
        // decides honour-or-burn — a rotated-but-expired token there resolves to a
        // dead tip and burns.
        if (!stored.revoked() && stored.isExpired(now)) {
            throw new InvalidRefreshTokenException("refresh token expired");
        }
        return advanceChain(stored, now);
    }

    // Cap the successor-chain walk. A well-behaved client is at most one lost
    // response behind, and every honoured replay re-points the presented token to
    // the current tip (so the next walk is a single hop). This bound is only a
    // guard against a pathological / corrupted chain.
    private static final int MAX_CHAIN_HOPS = 64;

    // Advance the session's refresh-token chain by one and hand the client the
    // resulting pair. For a LIVE token this is a normal rotation; for a revoked
    // (already-rotated) token it walks {@code replacedBy} to the live tip and
    // rotates that, re-pointing the presented token forward. A token whose chain
    // has no live tip (revoked by logout / theft burn, or expired) is theft →
    // burn the family.
    private TokenPair advanceChain(StoredRefreshToken presented, Instant now) {
        StoredRefreshToken tip = liveTip(presented, now);
        if (tip == null) {
            store.revokeAllForUser(presented.userId());
            throw new InvalidRefreshTokenException("refresh token already used");
        }

        String successorId = UUID.randomUUID().toString();
        byte[] secretBytes = new byte[SECRET_BYTES];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        if (!store.tryMarkRotated(tip.tokenId(), now, successorId)) {
            // A concurrent refresh rotated the tip between our read and here.
            // Re-resolve from the presented token and try again; the losing
            // secret is simply discarded (never persisted).
            return advanceChain(reRead(presented), now);
        }

        store.save(new StoredRefreshToken(
            successorId,
            tip.userId(),
            sha256(secret),
            now,
            now.plus(props.getRefreshTtl()),
            false,
            null,  // live token — not yet rotated
            null   // ...and has no successor yet
        ));
        // Keep a repeatedly-replayed stale token pointing at the freshest
        // successor so its next replay is a single hop onto a live tip.
        if (!presented.tokenId().equals(tip.tokenId())) {
            store.repoint(presented.tokenId(), successorId);
        }

        String access = mintAccessTokenForUser(tip.userId(), now);
        // The wire format keeps the id and secret together; only the hash persists.
        return new TokenPair(
            access,
            now.plus(props.getAccessTtl()).getEpochSecond(),
            successorId + "." + secret,
            now.plus(props.getRefreshTtl()).getEpochSecond()
        );
    }

    // Walk the successor chain from {@code presented} and return the tip iff it is
    // still live (usable). Returns null when the chain terminates at a dead token
    // — revoked by logout / theft burn (no {@code replacedBy}), expired, dangling,
    // or pathologically long — which the caller treats as theft.
    private StoredRefreshToken liveTip(StoredRefreshToken presented, Instant now) {
        StoredRefreshToken cur = presented;
        for (int hop = 0; hop < MAX_CHAIN_HOPS; hop++) {
            if (!cur.revoked()) {
                return cur.isExpired(now) ? null : cur;
            }
            String next = cur.replacedBy();
            if (next == null) {
                return null; // revoked with no successor: logout / theft burn
            }
            StoredRefreshToken succ = store.findById(next).orElse(null);
            if (succ == null) {
                return null; // dangling successor pointer
            }
            cur = succ;
        }
        return null; // chain too long — treat as corrupt, don't loop forever
    }

    private StoredRefreshToken reRead(StoredRefreshToken presented) {
        return store.findById(presented.tokenId()).orElse(presented);
    }

    // Mint a fresh access token for an existing session. Re-stamps identity from
    // the user record so a renamed/updated profile is reflected; the access token
    // must never outlive its source of truth.
    private String mintAccessTokenForUser(String userId, Instant now) {
        User user = users.findById(userId)
            .orElseThrow(() -> new InvalidRefreshTokenException("user no longer exists"));
        return mintAccessToken(user.userId(), user.email(), user.displayName(), now);
    }

    // Sign-out. Best-effort: an unparseable/unknown token is a no-op so logout
    // never fails the caller.
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

    private String mintAccessToken(String userId, String email, String displayName, Instant now) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(props.getIssuer())
            .subject(userId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(props.getAccessTtl())));
        if (email != null) {
            claims.claim("email", email);
        }
        if (displayName != null) {
            claims.claim("name", displayName);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(signingKeyBytes()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign session access token", e);
        }
    }

    private String issueRefreshToken(String userId, Instant now) {
        String tokenId = UUID.randomUUID().toString();
        byte[] secretBytes = new byte[SECRET_BYTES];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        store.save(new StoredRefreshToken(
            tokenId,
            userId,
            sha256(secret),
            now,
            now.plus(props.getRefreshTtl()),
            false,
            null, // live token — not yet rotated
            null  // ...and has no successor yet
        ));
        // The wire format keeps the id and secret together; only the secret's
        // hash is ever persisted.
        return tokenId + "." + secret;
    }

    private byte[] signingKeyBytes() {
        byte[] key = props.getSigningKey().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException(
                "app.session.signing-key must be at least 32 bytes for HS256");
        }
        return key;
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
