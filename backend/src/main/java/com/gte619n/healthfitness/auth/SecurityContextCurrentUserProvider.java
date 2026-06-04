package com.gte619n.healthfitness.auth;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

// Resolves the current user from whatever Authentication landed in the
// SecurityContext for this request. Two shapes are supported:
//   - JwtAuthenticationToken  — produced by the resource-server filter after
//     validating a Google ID token. `sub`, `email`, `name` come from claims.
//   - PreAuthenticatedAuthenticationToken with a CurrentUser principal —
//     produced by DevHeaderAuthFilter when app.auth.dev-mode=true.
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("no authenticated user on this request");
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            return fromJwt(jwt.getToken());
        }
        if (auth instanceof PreAuthenticatedAuthenticationToken pre
            && pre.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new IllegalStateException(
            "unexpected Authentication type: " + auth.getClass().getName());
    }

    private CurrentUser fromJwt(Jwt jwt) {
        return new CurrentUser(
            jwt.getSubject(),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsString("name"),
            jwt.getClaimAsString("picture")
        );
    }
}
