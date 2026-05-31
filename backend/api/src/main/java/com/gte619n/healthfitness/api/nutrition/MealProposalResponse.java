package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.MealProposal;
import java.util.List;

/**
 * Response for {@code POST /api/nutrition/capture/meal}: the stored photo
 * reference plus the itemized proposal. Nothing is persisted — the client
 * reviews/edits and saves via the M1 endpoints.
 */
public record MealProposalResponse(String photoRef, List<MealItemDto> items) {

    public static MealProposalResponse from(MealProposal proposal) {
        List<MealItemDto> items = proposal.items() == null
            ? List.of()
            : proposal.items().stream().map(MealItemDto::from).toList();
        return new MealProposalResponse(proposal.photoRef(), items);
    }
}
