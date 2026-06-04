package com.gte619n.healthfitness;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

// Accepts a token whose `aud` claim matches one of the configured client IDs
// (one per client: web, android phone, android wear). Empty config = reject all.
public class GoogleAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> allowedAudiences;

    public GoogleAudienceValidator(List<String> allowedAudiences) {
        this.allowedAudiences = List.copyOf(allowedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        if (aud != null) {
            for (String a : aud) {
                if (allowedAudiences.contains(a)) {
                    return OAuth2TokenValidatorResult.success();
                }
            }
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
            "invalid_token",
            "audience claim is not in the allowed list",
            null
        ));
    }
}
