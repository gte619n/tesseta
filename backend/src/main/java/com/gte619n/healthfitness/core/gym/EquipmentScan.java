package com.gte619n.healthfitness.core.gym;

import com.gte619n.healthfitness.core.equipment.BulkImportService.PreviewResult;
import java.time.Instant;

/**
 * IMPL-GYM-003: one gym-video equipment scan, persisted under
 * {@code users/{userId}/locations/{locationId}/equipmentScans/{scanId}}.
 *
 * <p>{@code preview} reuses the IMPL-GYM-002 bulk-import result shape so the same
 * review/confirm client UX renders it; it is null until {@link ScanStatus#READY}.
 * {@code videoRef} is the bare GCS object name and is cleared once the scan
 * reaches a terminal state (privacy + cost — a gym video may show bystanders).
 */
public record EquipmentScan(
    String userId,
    String locationId,
    String scanId,
    ScanStatus status,
    String videoRef,
    String mimeType,
    Long sizeBytes,
    Integer detectedCount,
    PreviewResult preview,
    String error,
    Instant createdAt,
    Instant updatedAt
) {
    public EquipmentScan withStatus(ScanStatus newStatus) {
        return new EquipmentScan(userId, locationId, scanId, newStatus, videoRef, mimeType,
            sizeBytes, detectedCount, preview, error, createdAt, Instant.now());
    }

    /** Terminal READY: attach the preview + count and drop the stored video ref. */
    public EquipmentScan ready(PreviewResult result, int count) {
        return new EquipmentScan(userId, locationId, scanId, ScanStatus.READY, null, mimeType,
            sizeBytes, count, result, null, createdAt, Instant.now());
    }

    /** Terminal FAILED: user-safe message + drop the stored video ref. */
    public EquipmentScan failed(String message) {
        return new EquipmentScan(userId, locationId, scanId, ScanStatus.FAILED, null, mimeType,
            sizeBytes, detectedCount, preview, message, createdAt, Instant.now());
    }
}
