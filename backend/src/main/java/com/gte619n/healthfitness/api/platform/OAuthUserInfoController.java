package com.gte619n.healthfitness.api.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// OIDC-style identity for the granted user (ADR-0020). Called by the third party
// with a platform access token. `email`/`name` are present only when the token
// carries them — the access token includes those claims solely when profile:read
// was granted — so this naturally reflects the consented scope.
@RestController
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class OAuthUserInfoController {

    @GetMapping("/oauth/userinfo")
    public Map<String, Object> userinfo(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub", jwt.getSubject());
        String email = jwt.getClaimAsString("email");
        if (email != null) {
            out.put("email", email);
        }
        String name = jwt.getClaimAsString("name");
        if (name != null) {
            out.put("name", name);
        }
        return out;
    }
}
