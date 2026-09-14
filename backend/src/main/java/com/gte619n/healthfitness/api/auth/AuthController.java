package com.gte619n.healthfitness.api.auth;

import com.gte619n.healthfitness.auth.AppAuthProperties;
import com.gte619n.healthfitness.auth.SessionTokenService;
import com.gte619n.healthfitness.auth.SessionTokenService.InvalidRefreshTokenException;
import com.gte619n.healthfitness.auth.SessionTokenService.TokenPair;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // The production GCP project id (matches the default of GCP_PROJECT_ID in
    // application.yml). Deployed prod runs with this value; UAT runs with the
    // emulator project (UAT_PROJECT, e.g. demo-uat), and local dev with anything
    // else. dev-login is only permitted OFF this project — see isDevLoginPermitted.
    private static final String PROD_PROJECT_ID = "health-fitness-160";

    private final SessionTokenService sessions;
    private final CurrentUserProvider currentUser;
    private final AppAuthProperties authProps;
    private final String projectId;

    public AuthController(
        SessionTokenService sessions,
        CurrentUserProvider currentUser,
        AppAuthProperties authProps,
        @Value("${app.gcp.project-id:}") String projectId
    ) {
        this.sessions = sessions;
        this.currentUser = currentUser;
        this.authProps = authProps;
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    /**
     * Second, independent guard for dev-login (SEC-008). dev-login mints a real
     * session for an arbitrary account with no Google sign-in, so a single
     * enable-flag flip in prod would be a full auth bypass. It is permitted only
     * when BOTH the enable-flag is set AND the deployment is non-prod (project id
     * is not the prod project). Fail closed: if the project id is unknown/blank we
     * treat it as prod and refuse.
     */
    private boolean isDevLoginPermitted() {
        return authProps.isDevLoginEnabled() && !isProdDeployment();
    }

    private boolean isProdDeployment() {
        return projectId.isBlank() || PROD_PROJECT_ID.equals(projectId);
    }

    /**
     * Fail loudly at boot if dev-login is enabled against the prod project — this
     * should never happen (a copy-pasted UAT env block would be the cause), and
     * the request-time guard already 404s it, but a startup ERROR surfaces the
     * misconfig for operators. We log rather than refuse to boot so a bad flag
     * cannot take prod down; the endpoint is inert regardless.
     */
    @PostConstruct
    void warnIfDevLoginEnabledInProd() {
        if (authProps.isDevLoginEnabled() && isProdDeployment()) {
            log.error("SECURITY: app.auth.dev-login-enabled=true on prod project '{}' — "
                + "dev-login is force-disabled (404) by the prod guard. Unset the flag.",
                projectId);
        }
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

    // UAT / local-only: mint a real session token pair for an arbitrary test
    // identity, with NO Google sign-in. Gated behind app.auth.dev-login-enabled,
    // which is false in production, so this 404s there. The web dev sign-in
    // (UAT_AUTH_ENABLED) and future Android instrumented tests both bootstrap
    // their session through this one endpoint, then send the returned access
    // token as a normal Bearer — validated by the same sessionDecoder Android
    // uses in production (UAT runs with dev-mode OFF).
    @PostMapping("/dev-login")
    public TokenResponse devLogin(@RequestBody(required = false) DevLoginRequest body) {
        // Two independent gates (SEC-008): the enable-flag AND a non-prod
        // deployment. The flag alone in prod does nothing.
        if (!isDevLoginPermitted()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
        requireEnabled();
        String userId = body == null || body.userId() == null || body.userId().isBlank()
            ? "uat-user" : body.userId();
        String email = body == null ? null : body.email();
        String name = body == null ? null : body.name();
        CurrentUser user = new CurrentUser(userId, email, name, null);
        return TokenResponse.of(sessions.issueFor(user));
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

    public record DevLoginRequest(String userId, String email, String name) {}

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
