package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.LabelProposal;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import java.util.List;

/**
 * Wire representation of a proposed packaged food drafted from a label OCR.
 * Maps onto the {@code POST /api/foods} create request the client submits to
 * persist it. {@code source} is the string name of the {@code FoodSource} enum
 * (always {@code "GEMINI_LABEL"} here).
 */
public record FoodDraftDto(
    String name,
    String brand,
    String barcode,
    MacrosDto macrosPer100g,
    List<ServingSizeDto> servingSizes,
    int defaultServingIndex,
    String source
) {
    public static FoodDraftDto from(LabelProposal.FoodDraft draft) {
        List<ServingSize> servings = draft.servingSizes();
        List<ServingSizeDto> dtos = servings == null
            ? List.of()
            : servings.stream().map(ServingSizeDto::from).toList();
        return new FoodDraftDto(
            draft.name(),
            draft.brand(),
            draft.barcode(),
            MacrosDto.from(draft.macrosPer100g()),
            dtos,
            draft.defaultServingIndex(),
            draft.source() != null ? draft.source().name() : null
        );
    }
}
