package com.gte619n.healthfitness.core.gym;

import java.util.Optional;

/** IMPL-GYM-003: persistence port for {@link EquipmentScan} rows. */
public interface EquipmentScanRepository {
    void save(EquipmentScan scan);

    Optional<EquipmentScan> findById(String userId, String locationId, String scanId);
}
