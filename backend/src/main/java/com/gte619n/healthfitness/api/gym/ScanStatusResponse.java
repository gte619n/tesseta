package com.gte619n.healthfitness.api.gym;

import com.gte619n.healthfitness.api.equipment.BulkImportPreviewResponse;
import com.gte619n.healthfitness.core.gym.EquipmentScan;

/**
 * IMPL-GYM-003: scan status the client polls. {@code preview} is populated only at
 * {@code READY} and reuses the bulk-import preview shape, so the same review table
 * renders it; {@code error} is set only at {@code FAILED}.
 */
public record ScanStatusResponse(
    String scanId,
    String status,
    Integer detectedCount,
    BulkImportPreviewResponse preview,
    String error
) {
    public static ScanStatusResponse from(EquipmentScan scan) {
        BulkImportPreviewResponse preview =
            scan.preview() == null ? null : BulkImportPreviewResponse.from(scan.preview());
        return new ScanStatusResponse(
            scan.scanId(), scan.status().name(), scan.detectedCount(), preview, scan.error());
    }
}
