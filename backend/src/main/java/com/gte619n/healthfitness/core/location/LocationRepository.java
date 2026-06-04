package com.gte619n.healthfitness.core.location;

import java.util.List;
import java.util.Optional;

public interface LocationRepository {
    Optional<Location> findById(String userId, String locationId);
    List<Location> findByUser(String userId, boolean includeInactive);
    void save(Location location);
    void delete(String userId, String locationId);
    void setDefault(String userId, String locationId);
    // Cross-user scan returning every location (across all users) whose
    // equipmentIds list contains the given equipmentId. Used by admin
    // merge to rewrite alias references. Expected to be infrequent.
    List<Location> findAllReferencing(String equipmentId);
}
