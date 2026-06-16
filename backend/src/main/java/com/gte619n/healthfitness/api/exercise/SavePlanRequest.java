package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.FrameSpec;
import java.util.List;

/** Body for the admin plan editor (PUT /{id}/plan): the full ordered plan. */
public record SavePlanRequest(List<FrameSpec> frames) {}
