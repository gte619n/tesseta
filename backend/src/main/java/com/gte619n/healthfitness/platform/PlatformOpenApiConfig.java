package com.gte619n.healthfitness.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// OpenAPI 3 metadata for the /v1 third-party API (ADR-0020, D16). Documents the
// Authorization Code + PKCE OAuth2 flow and the read scopes so an integrator's
// tooling can drive the flow from Swagger UI. Path scoping to /v1 is set via
// springdoc.paths-to-match in application.yml, so the first-party /api surface
// is never included.
@Configuration
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformOpenApiConfig {

    private static final String SECURITY_SCHEME = "tesseta-oauth";

    @Bean
    OpenAPI platformOpenApi() {
        Scopes scopes = new Scopes();
        for (PlatformScope scope : PlatformScope.values()) {
            scopes.addString(scope.wire(), scope.consentDescription());
        }

        OAuthFlow authorizationCode = new OAuthFlow()
            .authorizationUrl("/oauth/authorize")
            .tokenUrl("/oauth/token")
            .refreshUrl("/oauth/token")
            .scopes(scopes);

        SecurityScheme oauth = new SecurityScheme()
            .type(SecurityScheme.Type.OAUTH2)
            .description("Authorization Code + PKCE. Access tokens are RS256 JWTs;"
                + " validate against /oauth/jwks.json.")
            .flows(new OAuthFlows().authorizationCode(authorizationCode));

        return new OpenAPI()
            .info(new Info()
                .title("Tesseta Platform API")
                .version("v1")
                .description("Read-only third-party access to a user's Tesseta data "
                    + "(workouts, nutrition, medications & adherence, labs & metrics). "
                    + "User-delegated via OAuth 2.0 Authorization Code + PKCE. "
                    + "Scopes: " + String.join(", ",
                        Arrays.stream(PlatformScope.values()).map(PlatformScope::wire).toList())
                    + "."))
            .components(new Components().addSecuritySchemes(SECURITY_SCHEME, oauth))
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
