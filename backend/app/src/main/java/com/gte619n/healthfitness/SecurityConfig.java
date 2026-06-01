package com.gte619n.healthfitness;

import com.gte619n.healthfitness.auth.AppAuthProperties;
import com.gte619n.healthfitness.auth.AppCorsProperties;
import com.gte619n.healthfitness.auth.DevHeaderAuthFilter;
import com.gte619n.healthfitness.auth.UserProvisioningFilter;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.UserService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
@EnableConfigurationProperties({AppAuthProperties.class, AppCorsProperties.class})
public class SecurityConfig {

    @Bean
    JwtDecoder googleJwtDecoder(AppAuthProperties props) {
        if (props.isDevMode()) {
            return token -> {
                throw new BadJwtException(
                    "dev-mode: real JWT decoding disabled; use X-Dev-User header");
            };
        }
        NimbusJwtDecoder decoder =
            NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
            new GoogleAudienceValidator(props.getAllowedAudiences())
        ));
        return decoder;
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
                .requestMatchers("/api/me/**", "/api/me").authenticated()
                // Equipment catalog endpoints - authenticated users can browse
                .requestMatchers("/api/equipment/**").authenticated()
                // Admin endpoints require authentication (aspect handles admin check)
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
                    if (request.getRequestURI().startsWith("/api/webhooks/")) {
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
