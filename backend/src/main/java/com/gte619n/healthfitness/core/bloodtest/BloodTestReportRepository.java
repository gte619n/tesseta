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
}
