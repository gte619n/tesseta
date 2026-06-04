package com.gte619n.healthfitness.auth;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

// Dev/test-only bypass. Reads X-Dev-User and pre-authenticates the request,
// so downstream code can use CurrentUserProvider without a real Google JWT.
// Registered conditionally — see SecurityConfig. NEVER enabled in production.
public class DevHeaderAuthFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Dev-User";

    // SSE endpoints (e.g. the Goals chat stream) dispatch a second time on
    // the ASYNC dispatcher when the SseEmitter completes. OncePerRequestFilter
    // skips async dispatches by default, which would drop the pre-auth and
    // make Spring Security deny the re-dispatch. Re-run so the dev user stays
    // authenticated across the async boundary, mirroring how the real JWT
    // SecurityContext is persisted in production.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        String userId = request.getHeader(HEADER);
        if (userId != null && !userId.isBlank()) {
            // Mirror the userId into the email slot so admin checks (which key
            // on email, since prod Google JWTs always carry one) work in
            // dev/test mode — callers can pass an admin email as X-Dev-User
            // and have it satisfy AdminCheckAspect's allow-list.
            CurrentUser cu = new CurrentUser(userId, userId, null, null);
            PreAuthenticatedAuthenticationToken token = new PreAuthenticatedAuthenticationToken(
                cu, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            token.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(token);
        }
        chain.doFilter(request, response);
    }
}
