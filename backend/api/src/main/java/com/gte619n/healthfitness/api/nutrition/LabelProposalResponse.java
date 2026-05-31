package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.LabelProposal;

/**
 * Response for {@code POST /api/nutrition/capture/label}: the stored photo
 * reference plus a proposed packaged-food draft. Nothing is persisted — the
 * client confirms it into the catalog via {@code POST /api/foods}.
 */
public record LabelProposalResponse(String photoRef, FoodDraftDto food) {

    public static LabelProposalResponse from(LabelProposal proposal) {
        return new LabelProposalResponse(
            proposal.photoRef(),
            proposal.food() == null ? null : FoodDraftDto.from(proposal.food())
        );
    }
}
