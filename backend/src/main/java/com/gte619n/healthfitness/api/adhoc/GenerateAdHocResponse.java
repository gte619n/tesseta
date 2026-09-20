package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.core.adhoc.AdHocConstraintValidator;
import java.util.List;

/**
 * Preview of a generated ad-hoc workout (IMPL-ADHOC-01 D14). The {@code draft}
 * is editable and can be sent to {@code POST /adhoc-workouts} to save.
 * {@code constraintReport} surfaces any equipment violations the validator
 * flagged (empty when the generation was clean — D11).
 */
public record GenerateAdHocResponse(
    Draft draft,
    List<ViolationDto> constraintReport
) {
    public record Draft(
        String title,
        String summary,
        List<String> tags,
        EquipmentContextDto equipmentContext,
        Integer targetDurationMinutes,
        Integer estimatedDurationSeconds,
        DayResponse day
    ) {}

    public record ViolationDto(String blockId, int orderIndex, String exerciseId, String reason) {
        public static ViolationDto from(AdHocConstraintValidator.Violation v) {
            return new ViolationDto(v.blockId(), v.orderIndex(), v.exerciseId(), v.reason());
        }
    }
}
