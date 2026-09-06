package com.gte619n.healthfitness.core.progression;

import java.util.Optional;

/** Current block parameters per user. Firestore: {@code users/{uid}/progressionBlock/current}. */
public interface BlockParametersRepository {
    Optional<BlockParameters> find(String userId);
    void save(BlockParameters parameters);
}
