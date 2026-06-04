package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * One equipment requirement for an exercise, expressed as an "any-of" group of
 * Equipment-catalog ids. A gym satisfies the group if it has at least one
 * member. The exercise is executable at a gym when every requirement is
 * satisfied. A bodyweight exercise has zero requirements.
 *
 * <p>Example — "Barbell back squat" requires
 * {@code [{anyOf:[barbell]}, {anyOf:[squat-rack, power-rack]}, {anyOf:[plates]}]}.
 */
public record EquipmentRequirement(List<String> anyOf) {}
