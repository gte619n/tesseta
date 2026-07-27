package com.gte619n.healthfitness.core.platform;

import java.util.Optional;

// Persistence port for single-use authorization codes (ADR-0020).
public interface AuthorizationCodeStore {

    void save(AuthorizationCode code);

    // Atomically fetch-and-delete the code by its hash. Returns the code iff
    // this call removed it, so a replayed code (double-submit, or an attacker
    // racing the client) finds nothing and is rejected. Implementations MUST
    // make the read+delete atomic (a Firestore transaction), never a plain
    // read-then-delete.
    Optional<AuthorizationCode> consume(String codeHash);
}
