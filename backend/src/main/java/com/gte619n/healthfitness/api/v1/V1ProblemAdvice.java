package com.gte619n.healthfitness.api.v1;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

// RFC 7807 problem+json errors for the /v1 API (ADR-0020). Scoped to the api.v1
// package so it never changes error handling for the first-party /api
// controllers. Returning a ProblemDetail makes Spring emit
// `application/problem+json` with the standard {type,title,status,detail} shape.
@RestControllerAdvice(basePackages = "com.gte619n.healthfitness.api.v1")
public class V1ProblemAdvice {

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage() == null ? "not found" : e.getMessage());
    }

    // A bad cursor / date / limit / scope value is the client's fault → 400.
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage() == null ? "bad request" : e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN,
            "the access token is missing the scope required for this resource");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail statusException(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        return problem(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status, e.getReason());
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(status.getReasonPhrase());
        if (detail != null) {
            pd.setDetail(detail);
        }
        return pd;
    }
}
