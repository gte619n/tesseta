package com.gte619n.healthfitness.api.auth;

import com.gte619n.healthfitness.auth.SessionTokenService;
import com.gte619n.healthfitness.auth.SessionTokenService.InvalidRefreshTokenException;
import com.gte619n.healthfitness.auth.SessionTokenService.TokenPair;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Backend-issued session tokens for native clients (ADR-0010).
//
//   POST /api/auth/exchange  — authenticated with a Google ID token (in the
//       Authorization header, validated by the resource server). Mints the
//       first access+refresh pair. This is the ONLY call that needs a Google
//       token, so it's the only moment Android touches Credential Manager.
//   POST /api/auth/refresh   — public: trades a refresh token for a new pair.
//       This is the silent, UI-free path the client hits when a 401 comes back.
//   POST /api/auth/logout    — public: revokes a refresh token.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SessionTokenService sessions;
    private final CurrentUserProvider currentUser;

    public AuthController(SessionTokenService sessions, CurrentUserProvider currentUser) {
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    @PostMapping("/exchange")
    public TokenResponse exchange() {
        requireEnabled();
        return TokenResponse.of(sessions.issueFor(currentUser.get()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody RefreshRequest body) {
        requireEnabled();
        try {
            return TokenResponse.of(sessions.refresh(body == null ? null : body.refreshToken()));
        } catch (InvalidRefreshTokenException e) {
            // 401 signals the client to fall back to interactive sign-in.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest body) {
        if (body != null && body.refreshToken() != null) {
            sessions.revoke(body.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    private void requireEnabled() {
        if (!sessions.isEnabled()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "session tokens are not configured");
        }
    }

    public record RefreshRequest(String refreshToken) {}

    public record TokenResponse(
        String accessToken,
        long accessTokenExpiresAt,
        String refreshToken,
        long refreshTokenExpiresAt
    ) {
        static TokenResponse of(TokenPair pair) {
            return new TokenResponse(
                pair.accessToken(),
                pair.accessTokenExpiresAt(),
                pair.refreshToken(),
                pair.refreshTokenExpiresAt()
            );
        }
    }
}
