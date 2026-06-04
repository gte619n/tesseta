package com.gte619n.healthfitness.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.api.error.GlobalExceptionHandler.ErrorResponse;
import com.gte619n.healthfitness.core.error.FirestoreAccessException;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

// Unit-level coverage for the status mapping in GlobalExceptionHandler. The
// whole point of this advice is that RuntimeException subclasses don't all
// collapse into 500s, so each branch is pinned here.
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void responseStatusExceptionKeepsItsStatusAndReason() {
        ResponseEntity<ErrorResponse> res = handler.handleResponseStatus(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "no goal"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().message()).isEqualTo("no goal");
        assertThat(res.getBody().timestamp()).isNotNull();
    }

    @Test
    void accessDeniedMapsTo403() {
        ResponseEntity<ErrorResponse> res =
            handler.handleAccessDenied(new AccessDeniedException("nope"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error()).isEqualTo("forbidden");
    }

    @Test
    void noSuchElementMapsTo404() {
        ResponseEntity<ErrorResponse> res =
            handler.handleNoSuchElement(new NoSuchElementException("missing"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error()).isEqualTo("not_found");
    }

    @Test
    void illegalArgumentMapsTo400() {
        ResponseEntity<ErrorResponse> res =
            handler.handleIllegalArgument(new IllegalArgumentException("bad"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error()).isEqualTo("bad_request");
    }

    @Test
    void uncaughtRuntimeMapsTo500WithGenericMessage() {
        ResponseEntity<ErrorResponse> res =
            handler.handleRuntimeException(new RuntimeException("boom with secrets"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error()).isEqualTo("internal_error");
        // The raw message must not leak to the client.
        assertThat(res.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void firestoreMissingIndexGetsActionableMessage() {
        ResponseEntity<ErrorResponse> res = handler.handleFirestoreError(
            new FirestoreAccessException("read failed",
                new RuntimeException("FAILED_PRECONDITION: the query requires an index")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error()).isEqualTo("database_error");
        assertThat(res.getBody().message()).contains("index");
    }

    @Test
    void firestoreUnavailableAsksForRetry() {
        ResponseEntity<ErrorResponse> res = handler.handleFirestoreError(
            new FirestoreAccessException("read failed",
                new RuntimeException("UNAVAILABLE: backend down")));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().message()).contains("retry");
    }

    @Test
    void firestoreUnknownCauseGetsGenericDatabaseMessage() {
        ResponseEntity<ErrorResponse> res = handler.handleFirestoreError(
            new FirestoreAccessException("read failed", new RuntimeException("weird")));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().message()).isEqualTo("Database error occurred.");
    }
}
