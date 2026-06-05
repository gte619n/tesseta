package com.gte619n.healthfitness;

import com.gte619n.healthfitness.auth.AppAuthProperties;
import com.gte619n.healthfitness.auth.AppCorsProperties;
import com.gte619n.healthfitness.auth.AppSessionProperties;
import com.gte619n.healthfitness.auth.DevHeaderAuthFilter;
import com.gte619n.healthfitness.auth.UserProvisioningFilter;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.UserService;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// IMPL-02: backend is a JWT resource server. Every /api/me/** request must
// carry a Google ID token signed by accounts.google.com with one of the
// configured client IDs as its audience. /api/hello and /actuator/health
// remain public. Dev-mode (app.auth.dev-mode=true, only set in
// application-test.yml) installs an X-Dev-User pre-authentication filter so
// tests don't need real Google tokens — and replaces the real JWT decoder
// with a stub that throws so the bean doesn't try to fetch the Google OIDC
// discovery document during test startup.
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({
    AppAuthProperties.class, AppCorsProperties.class, AppSessionProperties.class})
public class SecurityConfig {

    // The resource server validates two distinct token families and routes by
    // the `iss` claim (ADR-0010):
    //   - accounts.google.com  → Google ID tokens (web client, and the Android
    //     /api/auth/exchange call that bootstraps a session)
    //   - app.session.issuer   → our own HS256 access tokens (Android phone +
    //     Wear, on every other request)
    // Both decode to a JwtAuthenticationToken with `sub`=userId, so CurrentUser
    // resolution and user provisioning are identical regardless of family.
    @Bean
    JwtDecoder jwtDecoder(AppAuthProperties authProps, AppSessionProperties sessionProps) {
        if (authProps.isDevMode()) {
            return token -> {
                throw new BadJwtException(
                    "dev-mode: real JWT decoding disabled; use X-Dev-User header");
            };
        }
        JwtDecoder google = googleDecoder(authProps);
        if (!sessionProps.isEnabled()) {
            // No signing key configured — only Google tokens are accepted.
            return google;
        }
        JwtDecoder session = sessionDecoder(sessionProps);
        String sessionIssuer = sessionProps.getIssuer();
        return token -> sessionIssuer.equals(peekIssuer(token))
            ? session.decode(token)
            : google.decode(token);
    }

    private static JwtDecoder googleDecoder(AppAuthProperties props) {
        NimbusJwtDecoder decoder =
            NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
            new GoogleAudienceValidator(props.getAllowedAudiences())
        ));
        return decoder;
    }

    private static JwtDecoder sessionDecoder(AppSessionProperties props) {
        SecretKeySpec key = new SecretKeySpec(
            props.getSigningKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtIssuerValidator(props.getIssuer())
        ));
        return decoder;
    }

    // Reads the unverified `iss` claim to pick a decoder. The chosen decoder
    // then verifies the signature, so a forged issuer only mis-routes to a
    // decoder that will reject it — never a trust bypass.
    private static String peekIssuer(String token) {
        try {
            Object iss = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
            return iss == null ? null : iss.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Bean
    SecurityFilterChain filterChain(
        HttpSecurity http,
        AppAuthProperties authProps,
        UserService userService,
        CurrentUserProvider currentUserProvider,
        UrlBasedCorsConfigurationSource corsSource
    ) throws Exception {
        UserProvisioningFilter provisioning =
            new UserProvisioningFilter(userService, currentUserProvider);

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/hello", "/actuator/health", "/actuator/info").permitAll()
                // Webhook endpoint is authenticated via shared secret in the
                // Authorization header, not via Google ID token, so it
                // bypasses the JWT filter entirely.
                .requestMatchers("/api/webhooks/**").permitAll()
                // Session-token endpoints (ADR-0010). `refresh`/`logout` carry
                // an opaque refresh token in the body, not a Google JWT, so they
                // are public; `exchange` requires a valid Google ID token.
                .requestMatchers("/api/auth/refresh", "/api/auth/logout").permitAll()
                // UAT/dev-only bootstrap: carries no token (it mints one). The
                // controller itself 404s unless app.auth.dev-mode is true.
                .requestMatchers("/api/auth/dev-login").permitAll()
                .requestMatchers("/api/auth/exchange").authenticated()
                .requestMatchers("/api/me/**", "/api/me").authenticated()
                // Equipment catalog endpoints - authenticated users can browse
                .requestMatchers("/api/equipment/**").authenticated()
                // Exercise catalog endpoints - authenticated users can browse
                .requestMatchers("/api/exercises/**", "/api/exercises").authenticated()
                // Admin endpoints require authentication; the admin check itself
                // is enforced by @AdminOnly (method security) on the controllers.
                .requestMatchers("/api/admin/**").authenticated()
                .requestMatchers("/api/drugs", "/api/drugs/**").authenticated()
                // Food catalog + barcode lookup operate on the current user
                .requestMatchers("/api/foods/**", "/api/foods").authenticated()
                // Nutrition capture (meal/label photo) operates on the current user
                .requestMatchers("/api/nutrition/capture/**").authenticated()
                .anyRequest().denyAll()
            )
            // Webhook endpoints arrive with `Authorization: Bearer <secret>`
            // where <secret> is our shared webhook secret, NOT a Google ID
            // token. The default bearer-token resolver would try to JWT-
            // decode it and fail with 401 before our controller could see
            // the request. Skip resolution for /api/webhooks/** so the
            // request reaches the controller's own auth check.
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(request -> {
                    String uri = request.getRequestURI();
                    // Webhooks authenticate via shared secret; the public
                    // session endpoints (refresh/logout) carry an opaque token
                    // in the body. Skipping resolution stops the resource server
                    // from eagerly JWT-decoding (and 401-ing on) a stray header
                    // before the controller runs.
                    if (uri.startsWith("/api/webhooks/")
                        || uri.equals("/api/auth/refresh")
                        || uri.equals("/api/auth/logout")
                        || uri.equals("/api/auth/dev-login")) {
                        return null;
                    }
                    return new DefaultBearerTokenResolver().resolve(request);
                })
                .jwt(j -> {}))
            .addFilterAfter(provisioning, BearerTokenAuthenticationFilter.class)
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());

        if (authProps.isDevMode()) {
            http.addFilterBefore(new DevHeaderAuthFilter(),
                UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
