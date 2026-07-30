package com.gte619n.healthfitness.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// OpenAPI 3 metadata for the /v1 third-party API (ADR-0020, D16). Documents the
// Authorization Code + PKCE OAuth2 flow and the read scopes so an integrator's
// tooling can drive the flow from Swagger UI. Path scoping to /v1 is set via
// springdoc.paths-to-match in application.yml, so the first-party /api surface
// is never included.
//
// Two beans:
//   - platformOpenApi()   : the static contract (info, servers, security scheme).
//   - platformV1Responses(): an OpenApiCustomizer that stamps every operation
//     with the cross-cutting concerns that are enforced by servlet filters, not
//     controller code, and so are invisible to springdoc's scan — the RFC 7807
//     error responses (V1ProblemAdvice) and the RateLimit-* headers
//     (V1RateLimitFilter). Doing it once here keeps the controllers free of
//     repetitive @ApiResponse boilerplate.
@Configuration
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformOpenApiConfig {

    private static final String SECURITY_SCHEME = "tesseta-oauth";
    private static final String PROBLEM_SCHEMA = "ProblemDetail";
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private final AppPlatformProperties props;

    public PlatformOpenApiConfig(AppPlatformProperties props) {
        this.props = props;
    }

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
                + " validate against /oauth/jwks.json. Discover endpoints at"
                + " /.well-known/oauth-authorization-server.")
            .flows(new OAuthFlows().authorizationCode(authorizationCode));

        OpenAPI api = new OpenAPI()
            .info(new Info()
                .title("Tesseta Platform API")
                .version("v1")
                .description("Read-only third-party access to a user's Tesseta data "
                    + "(workouts, nutrition, medications & adherence, labs & metrics). "
                    + "User-delegated via OAuth 2.0 Authorization Code + PKCE. "
                    + "List endpoints are keyset-paginated (`{data, nextCursor, hasMore}`) "
                    + "and support incremental pull via `updatedSince`. Errors are "
                    + "RFC 7807 `application/problem+json`. Scopes: " + String.join(", ",
                        Arrays.stream(PlatformScope.values()).map(PlatformScope::wire).toList())
                    + ".")
                .contact(new Contact().name("Tesseta API Support").email("api@tesseta.com"))
                .license(new License().name("Tesseta Platform API Terms")
                    .url("https://tesseta.com/terms"))
                .termsOfService("https://tesseta.com/terms"))
            // The ProblemDetail schema is registered in the customizer below, not
            // here: springdoc rebuilds components.schemas from the scanned DTOs
            // after this bean is applied, which would drop a schema added here.
            .components(new Components().addSecuritySchemes(SECURITY_SCHEME, oauth))
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
            .servers(servers());

        if (props.getDocsUrl() != null && !props.getDocsUrl().isBlank()) {
            api.externalDocs(new ExternalDocumentation()
                .description("Integration guide & rendered reference")
                .url(props.getDocsUrl()));
        }
        return api;
    }

    // Stamp the filter-enforced concerns onto every generated operation.
    @Bean
    OpenApiCustomizer platformV1Responses() {
        return openApi -> {
            // Register the RFC 7807 schema here (post-scan): the error responses
            // below $ref it, and springdoc rebuilds components.schemas from the
            // scanned DTOs, so it must be added after that.
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemDetailSchema());

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) ->
                item.readOperations().forEach(op -> decorate(op, path)));
        };
    }

    private void decorate(Operation op, String path) {
        ApiResponses responses = op.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            op.setResponses(responses);
        }
        // The success response already exists (springdoc inferred it from the
        // return type); attach the rate-limit headers that V1RateLimitFilter emits.
        responses.forEach((code, response) -> addRateLimitHeaders(response));

        responses.addApiResponse("400", problem("The request has an invalid "
            + "parameter (bad cursor, date, limit, or filter value)."));
        responses.addApiResponse("401", problem("Missing, expired, or invalid "
            + "access token."));
        responses.addApiResponse("403", problem("The access token is valid but "
            + "lacks the scope this resource requires."));
        // A 404 is only reachable on by-id lookups (paths with a {pathVariable}).
        if (path.contains("{")) {
            responses.addApiResponse("404", problem("No such resource for this user."));
        }
        responses.addApiResponse("429", addRateLimitHeaders(
            problem("Per-client/per-user rate limit exceeded; see Retry-After."))
            .addHeaderObject("Retry-After", new Header()
                .description("Seconds until the rate-limit window resets.")
                .schema(new IntegerSchema())));
    }

    private ApiResponse problem(String description) {
        return new ApiResponse()
            .description(description)
            .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE, new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
    }

    private static ApiResponse addRateLimitHeaders(ApiResponse response) {
        return response
            .addHeaderObject("RateLimit-Limit", new Header()
                .description("Request budget for the current fixed window.")
                .schema(new IntegerSchema()))
            .addHeaderObject("RateLimit-Remaining", new Header()
                .description("Requests remaining in the current window.")
                .schema(new IntegerSchema()))
            .addHeaderObject("RateLimit-Reset", new Header()
                .description("Seconds until the current window resets.")
                .schema(new IntegerSchema()));
    }

    // RFC 7807 problem+json. Modeled explicitly because no controller returns
    // ProblemDetail directly, so springdoc would not otherwise emit the schema.
    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
            .description("RFC 7807 problem detail (application/problem+json).")
            .addProperty("type", new StringSchema()
                .description("A URI reference identifying the problem type.")
                .example("about:blank"))
            .addProperty("title", new StringSchema()
                .description("Short, human-readable summary of the problem type."))
            .addProperty("status", new IntegerSchema()
                .description("The HTTP status code."))
            .addProperty("detail", new StringSchema()
                .description("Human-readable explanation specific to this occurrence."))
            .addProperty("instance", new StringSchema()
                .description("A URI reference identifying the specific occurrence."));
    }

    private List<Server> servers() {
        Server relative = new Server().url("/").description("Same-origin (relative)");
        if (props.getPublicBaseUrl() != null && !props.getPublicBaseUrl().isBlank()) {
            return List.of(
                new Server().url(props.getPublicBaseUrl()).description("Production"),
                relative);
        }
        return List.of(relative);
    }
}
