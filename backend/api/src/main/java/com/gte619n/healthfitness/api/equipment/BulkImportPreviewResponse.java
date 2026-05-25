package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.equipment.BulkImportService;
import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import java.util.List;

public record BulkImportPreviewResponse(
    List<Item> items,
    Summary summary
) {
    public record Item(
        int index,
        ParsedEquipment parsed,
        Match match,                    // nullable
        String action                   // "MATCH_AUTO" | "MATCH_SUGGESTED" | "CREATE_NEW"
    ) {}

    public record Match(
        String equipmentId,
        String name,
        double score,
        String reason
    ) {}

    public record Summary(int total, int matched, int suggestedMatches, int newSubmissions) {}

    public static BulkImportPreviewResponse from(BulkImportService.PreviewResult result) {
        List<Item> items = result.items().stream()
            .map(BulkImportPreviewResponse::toItem)
            .toList();
        BulkImportService.PreviewSummary s = result.summary();
        Summary summary = new Summary(
            s.total(),
            s.matched(),
            s.suggestedMatches(),
            s.newSubmissions()
        );
        return new BulkImportPreviewResponse(items, summary);
    }

    private static Item toItem(BulkImportService.PreviewItem pi) {
        Match match = null;
        if (pi.match() != null) {
            BulkImportService.MatchInfo m = pi.match();
            match = new Match(m.equipmentId(), m.name(), m.score(), m.reason());
        }
        return new Item(pi.index(), pi.parsed(), match, pi.action().name());
    }
}
