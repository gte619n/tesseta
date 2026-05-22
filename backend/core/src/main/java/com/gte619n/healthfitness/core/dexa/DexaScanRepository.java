package com.gte619n.healthfitness.core.dexa;

import java.util.List;
import java.util.Optional;

public interface DexaScanRepository {
    void save(DexaScan scan);

    Optional<DexaScan> findById(String userId, String scanId);

    // Newest-first.
    List<DexaScan> findByUser(String userId);

    void delete(String userId, String scanId);
}
