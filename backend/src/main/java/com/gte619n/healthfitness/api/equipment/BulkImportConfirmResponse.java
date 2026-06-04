package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.equipment.BulkImportService;
import java.util.List;

public record BulkImportConfirmResponse(
    List<Created> created,
    List<Matched> matched,
    int addedToLocation,
    int skipped,
    List<Failed> failed
) {
    public record Created(String equipmentId, String name, String status) {}
    public record Matched(String equipmentId, String name) {}
    public record Failed(int index, String name, String reason) {}

    public static BulkImportConfirmResponse from(
        BulkImportService.ConfirmResult result,
        int addedToLocation
    ) {
        List<Created> created = result.created().stream()
            .map(c -> new Created(c.equipmentId(), c.name(), c.status()))
            .toList();
        List<Matched> matched = result.matched().stream()
            .map(m -> new Matched(m.equipmentId(), m.name()))
            .toList();
        List<Failed> failed = result.failed().stream()
            .map(f -> new Failed(f.index(), f.name(), f.reason()))
            .toList();
        return new BulkImportConfirmResponse(
            created, matched, addedToLocation, result.skipped(), failed);
    }
}
