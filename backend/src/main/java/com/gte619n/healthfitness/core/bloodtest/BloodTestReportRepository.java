package com.gte619n.healthfitness.core.bloodtest;

import java.util.List;
import java.util.Optional;

public interface BloodTestReportRepository {
    void save(BloodTestReport report);

    Optional<BloodTestReport> findById(String userId, String reportId);

    // Newest-first by sampleDate.
    List<BloodTestReport> findByUser(String userId);

    // Returns an existing report matching this user + PDF content hash,
    // if any. Used to short-circuit re-uploads of the same file before
    // we spend money on a Gemini call.
    Optional<BloodTestReport> findByContentHash(String userId, String contentHash);

    void delete(String userId, String reportId);

    // Atomically reserve this user + PDF content hash for an in-flight upload.
    // Returns true if the caller won the reservation, false if another upload
    // of the identical PDF already holds it. This closes the check-then-act
    // race in the upload service: findByContentHash + save straddle a slow
    // Gemini call, so concurrent uploads of the same bytes would each pass the
    // find check and all persist. Release via releaseContentHash once the
    // upload finishes (success or failure).
    //
    // Default is a no-op that always grants the reservation, so in-memory test
    // doubles keep their current behavior without overriding.
    default boolean tryReserveContentHash(String userId, String contentHash) {
        return true;
    }

    // Release a reservation previously taken by tryReserveContentHash.
    default void releaseContentHash(String userId, String contentHash) {}
}
