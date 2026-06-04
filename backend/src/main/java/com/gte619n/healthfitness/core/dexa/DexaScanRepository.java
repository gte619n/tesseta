package com.gte619n.healthfitness.core.dexa;

import java.util.List;
import java.util.Optional;

public interface DexaScanRepository {
    void save(DexaScan scan);

    Optional<DexaScan> findById(String userId, String scanId);

    // Newest-first.
    List<DexaScan> findByUser(String userId);

    // Returns an existing scan matching this user + PDF content hash,
    // if any. Used to short-circuit re-uploads of the same file before
    // we spend money on a Gemini call.
    Optional<DexaScan> findByContentHash(String userId, String contentHash);

    void delete(String userId, String scanId);
}
