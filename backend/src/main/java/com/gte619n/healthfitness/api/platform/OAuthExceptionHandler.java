package com.gte619n.healthfitness.api.platform;

import com.gte619n.healthfitness.platform.OAuthException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Renders OAuthException as the RFC 6749 §5.2 JSON error body
// ({"error","error_description"}) with the mapped status (401 for
// invalid_client, 400 otherwise). Scoped to the platform api package so it never
// alters error handling for the first-party /api controllers.
@RestControllerAdvice(basePackages = "com.gte619n.healthfitness.api.platform")
public class OAuthExceptionHandler {

    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<Map<String, String>> handle(OAuthException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.error());
        body.put("error_description", e.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status())
            .header("Cache-Control", "no-store");
        if (e.status().value() == 401) {
            builder.header("WWW-Authenticate", "Basic realm=\"oauth\"");
        }
        return builder.body(body);
    }
}
