package com.gte619n.healthfitness.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

// Keeps the two token families in their own lanes (ADR-0020). A platform access
// token is a valid JWT, so without this it would satisfy `.authenticated()` on
// the first-party /api surface and let a third-party app read everything the
// owner can — bypassing scopes entirely. This filter enforces the audience
// boundary that the coarse `.authenticated()` matcher cannot:
//
//   - a platform-issued token may ONLY reach the platform zone
//     (/v1/** and /oauth/userinfo); anything else is 403.
//   - the platform zone (/v1/**) rejects a first-party token, so the app's own
//     session can't wander into the delegated API either.
//
// Runs after authentication is populated; unauthenticated requests fall through
// to the normal authorization rules (which 401 the protected ones).
public class PlatformAudienceFilter extends OncePerRequestFilter {

    private final String platformIssuer;

    public PlatformAudienceFilter(String platformIssuer) {
        this.platformIssuer = platformIssuer;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean platformZone = uri.startsWith("/v1/") || uri.equals("/oauth/userinfo");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated();
        boolean isPlatformToken = authenticated
            && auth.getPrincipal() instanceof Jwt jwt
            && platformIssuer.equals(jwt.getClaimAsString("iss"));

        if (isPlatformToken && !platformZone) {
            deny(response, "this token may only access the /v1 platform API");
            return;
        }
        if (uri.startsWith("/v1/") && authenticated && !isPlatformToken) {
            deny(response, "the /v1 platform API requires a platform access token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"error\":\"insufficient_scope\",\"error_description\":\"" + message + "\"}");
    }
}
