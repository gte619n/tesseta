package com.gte619n.healthfitness.api.platform;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.platform.AppPlatformProperties;
import com.gte619n.healthfitness.platform.OAuthAuthorizationService;
import com.gte619n.healthfitness.platform.OAuthAuthorizationService.AuthorizationRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

// The authorization endpoint (ADR-0020). Unlike a classic browser-redirect
// /authorize, this is a JSON API driven by the first-party web app, which is
// already signed in as the user and renders the consent screen:
//
//   GET  /oauth/authorize          → validate the request, return consent
//                                     metadata (client, scopes to display).
//   POST /oauth/authorize/consent  → on approval, mint a code and return the
//                                     redirect URL the web app sends the browser
//                                     to; on denial, the RFC error redirect.
//
// Both require a FIRST-PARTY session (the logged-in user). A third-party
// platform token must never drive consent — that would let an app escalate its
// own grant — so we reject the platform issuer explicitly.
@RestController
@RequestMapping("/oauth")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class OAuthAuthorizationController {

    private final OAuthAuthorizationService authService;
    private final CurrentUserProvider currentUser;
    private final AppPlatformProperties props;

    public OAuthAuthorizationController(
        OAuthAuthorizationService authService,
        CurrentUserProvider currentUser,
        AppPlatformProperties props
    ) {
        this.authService = authService;
        this.currentUser = currentUser;
        this.props = props;
    }

    @GetMapping("/authorize")
    public ConsentResponse authorize(
        Authentication authentication,
        @RequestParam(name = "response_type", required = false) String responseType,
        @RequestParam(name = "client_id", required = false) String clientId,
        @RequestParam(name = "redirect_uri", required = false) String redirectUri,
        @RequestParam(name = "scope", required = false) String scope,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "code_challenge", required = false) String codeChallenge,
        @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod
    ) {
        requireFirstParty(authentication);
        CurrentUser user = currentUser.get();
        AuthorizationRequest req = authService.validate(
            user.userId(), clientId, redirectUri, responseType,
            scope, codeChallenge, codeChallengeMethod, state);
        return new ConsentResponse(
            req.client().clientId(),
            req.client().name(),
            req.client().logoUrl(),
            req.redirectUri(),
            state,
            req.previouslyGranted(),
            req.scopeDescriptions().stream()
                .map(d -> new ScopeItem(d.scope(), d.description()))
                .toList());
    }

    @PostMapping("/authorize/consent")
    public ConsentResult consent(Authentication authentication, @RequestBody ConsentDecision body) {
        requireFirstParty(authentication);
        CurrentUser user = currentUser.get();
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing consent body");
        }

        AuthorizationRequest req = authService.validate(
            user.userId(), body.clientId(), body.redirectUri(), "code",
            body.scope(), body.codeChallenge(), body.codeChallengeMethod(), body.state());

        if (!body.approve()) {
            // RFC 6749 §4.1.2.1 — the user denied; redirect back with the error.
            return new ConsentResult(errorRedirect(body.redirectUri(), "access_denied", body.state()));
        }

        String code = authService.issueCode(req, user.userId(), user.email(), user.displayName());
        return new ConsentResult(successRedirect(body.redirectUri(), code, body.state()));
    }

    // First-party guard: the consent flow is for the account owner, not a
    // delegated app. Reject any bearer minted by the platform issuer.
    private void requireFirstParty(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
            && props.getIssuer().equals(jwt.getClaimAsString("iss"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "a third-party token cannot drive the consent flow");
        }
    }

    private static String successRedirect(String redirectUri, String code, String state) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(redirectUri).queryParam("code", code);
        if (state != null && !state.isBlank()) {
            b.queryParam("state", state);
        }
        return b.build().toUriString();
    }

    private static String errorRedirect(String redirectUri, String error, String state) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(redirectUri).queryParam("error", error);
        if (state != null && !state.isBlank()) {
            b.queryParam("state", state);
        }
        return b.build().toUriString();
    }

    public record ConsentResponse(
        String clientId,
        String clientName,
        String logoUrl,
        String redirectUri,
        String state,
        boolean previouslyGranted,
        List<ScopeItem> scopes
    ) {}

    public record ScopeItem(String scope, String description) {}

    public record ConsentDecision(
        boolean approve,
        String clientId,
        String redirectUri,
        String scope,
        String state,
        String codeChallenge,
        String codeChallengeMethod
    ) {}

    public record ConsentResult(String redirectUri) {}
}
