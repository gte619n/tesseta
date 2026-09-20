package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramAssembler;
import com.gte619n.healthfitness.core.adhoc.AdHocConstraintValidator;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutGenerator;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutService;
import com.gte619n.healthfitness.core.adhoc.EquipmentContext;
import com.gte619n.healthfitness.core.adhoc.EquipmentPresetCatalog;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDurationEstimator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ad-hoc workout generation (IMPL-ADHOC-01 Phase 2). {@code POST /generate}
 * resolves the equipment allow-list, asks the generator for a single-day
 * workout, estimates its duration, validates it against the equipment (D11),
 * and returns an editable preview draft.
 *
 * <p>The generator ({@link AdHocWorkoutGenerator}) is gated behind
 * {@code app.adhoc-workouts.enabled}; when absent (flag off / no API key) this
 * returns 503 rather than failing to start, mirroring the other gated Gemini
 * features.
 */
@RestController
@RequestMapping("/api/me/adhoc-workouts")
public class AdHocGenerationController {

    private final CurrentUserProvider currentUser;
    private final ObjectProvider<AdHocWorkoutGenerator> generator;
    private final EquipmentPresetCatalog presets;
    private final AdHocConstraintValidator constraints;
    private final WorkoutProgramAssembler assembler;

    public AdHocGenerationController(
        CurrentUserProvider currentUser,
        ObjectProvider<AdHocWorkoutGenerator> generator,
        EquipmentPresetCatalog presets,
        AdHocConstraintValidator constraints,
        WorkoutProgramAssembler assembler
    ) {
        this.currentUser = currentUser;
        this.generator = generator;
        this.presets = presets;
        this.constraints = constraints;
        this.assembler = assembler;
    }

    @PostMapping("/generate")
    public GenerateAdHocResponse generate(@RequestBody GenerateAdHocRequest body) {
        String userId = currentUser.get().userId();
        AdHocWorkoutGenerator gen = generator.getIfAvailable();
        if (gen == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Ad-hoc workout generation is not enabled.");
        }
        if (body == null || body.prompt() == null || body.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A prompt is required.");
        }

        Set<String> equipmentIds = presets.resolve(userId, body.presetId(), body.equipmentIds());
        AdHocWorkoutGenerator.Generated generated =
            gen.generate(userId, body.prompt(), body.targetDurationMinutes(), equipmentIds);

        WorkoutDay day = AdHocWorkoutService.normalizeDay(generated.day());
        int estimate = WorkoutDurationEstimator.estimateSeconds(day);
        AdHocConstraintValidator.Report report = constraints.validate(day, equipmentIds);

        EquipmentContext ctx = new EquipmentContext(
            body.presetId(), presetLabel(body), List.copyOf(equipmentIds), body.freeText());

        GenerateAdHocResponse.Draft draft = new GenerateAdHocResponse.Draft(
            generated.title(),
            generated.summary(),
            generated.tags(),
            com.gte619n.healthfitness.api.adhoc.EquipmentContextDto.from(ctx),
            body.targetDurationMinutes(),
            estimate,
            assembler.day(userId, day));

        return new GenerateAdHocResponse(
            draft,
            report.violations().stream().map(GenerateAdHocResponse.ViolationDto::from).toList());
    }

    private static String presetLabel(GenerateAdHocRequest body) {
        if (body.presetId() != null && !body.presetId().isBlank()) {
            return switch (body.presetId()) {
                case "hotel-gym" -> "Hotel gym";
                case "home" -> "Home";
                case "bodyweight" -> "Bodyweight only";
                case "full-gym" -> "Full gym";
                default -> body.presetId();
            };
        }
        return body.freeText();
    }
}
