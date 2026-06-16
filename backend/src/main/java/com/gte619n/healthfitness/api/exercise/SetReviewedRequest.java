package com.gte619n.healthfitness.api.exercise;

/** Body for {@code POST /api/admin/exercises/{id}/reviewed} (IMPL-20). */
public record SetReviewedRequest(boolean reviewed) {}
