package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/nutrition")
public class NutritionController {

    private final CurrentUserProvider currentUser;
    private final NutritionService nutrition;
    private final MacroTargetService targets;

    public NutritionController(
        CurrentUserProvider currentUser,
        NutritionService nutrition,
        MacroTargetService targets
    ) {
        this.currentUser = currentUser;
        this.nutrition = nutrition;
        this.targets = targets;
    }

    // ----- Legacy day-total quick entry --------------------------------

    @PostMapping
    public ResponseEntity<LogResponse> upsert(@RequestBody LogRequest body) {
        if (body == null || body.date() == null) {
            throw new IllegalArgumentException("date is required");
        }
        String userId = currentUser.get().userId();
        NutritionDailyLog log = nutrition.logDay(
            userId,
            body.date(),
            body.proteinGrams(),
            body.carbsGrams(),
            body.fatGrams(),
            body.fiberGrams(),
            body.sugarGrams(),
            body.caloriesKcal());
        return ResponseEntity.status(201).body(LogResponse.from(log));
    }

    @GetMapping
    public List<LogResponse> list(
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        String userId = currentUser.get().userId();
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return nutrition.findRange(userId, start, end).stream()
            .map(LogResponse::from)
            .toList();
    }

    @GetMapping("/today")
    public ResponseEntity<LogResponse> today() {
        String userId = currentUser.get().userId();
        return nutrition.findByDate(userId, LocalDate.now())
            .map(log -> ResponseEntity.ok(LogResponse.from(log)))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // ----- Macro target -------------------------------------------------

    @GetMapping("/target")
    public ResponseEntity<MacrosDto> getTarget() {
        String userId = currentUser.get().userId();
        return targets.getActive(userId)
            .map(t -> ResponseEntity.ok(MacrosDto.from(t.macros())))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/target")
    public MacrosDto setTarget(@RequestBody MacrosDto body) {
        if (body == null) {
            throw new IllegalArgumentException("target macros are required");
        }
        String userId = currentUser.get().userId();
        MacroTarget target = targets.setTarget(userId, body.toMacros());
        return MacrosDto.from(target.macros());
    }

    // ----- Day view + entries ------------------------------------------

    @GetMapping("/{date}")
    public DayResponse day(@PathVariable LocalDate date) {
        String userId = currentUser.get().userId();
        List<FoodEntry> entries = nutrition.listEntries(userId, date);

        Macros totals = nutrition.findByDate(userId, date)
            .map(NutritionController::macrosOf)
            .orElseGet(Macros::zero);

        MacrosDto target = targets.getActive(userId)
            .map(t -> MacrosDto.from(t.macros()))
            .orElse(null);

        List<MealGroup> meals = new ArrayList<>();
        for (MealType meal : MealType.values()) {
            List<EntryResponse> mealEntries = new ArrayList<>();
            Macros subtotal = Macros.zero();
            for (FoodEntry e : entries) {
                if (e.meal() == meal) {
                    mealEntries.add(EntryResponse.from(e));
                    subtotal = subtotal.plus(e.macros());
                }
            }
            meals.add(new MealGroup(meal, MacrosDto.from(subtotal), mealEntries));
        }

        return new DayResponse(date, MacrosDto.from(totals), target, meals);
    }

    @PostMapping("/{date}/entries")
    public ResponseEntity<EntryResponse> addEntry(
        @PathVariable LocalDate date,
        @RequestBody AddEntryRequest body
    ) {
        if (body == null || body.meal() == null) {
            throw new IllegalArgumentException("meal is required");
        }
        String userId = currentUser.get().userId();
        FoodEntry entry = nutrition.addEntry(
            userId,
            date,
            body.meal(),
            body.foodId(),
            body.foodName(),
            body.servingLabel(),
            body.servingGrams(),
            body.quantity(),
            body.macros() != null ? body.macros().toMacros() : null,
            body.source());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(entry));
    }

    @PatchMapping("/{date}/entries/{entryId}")
    public EntryResponse updateEntry(
        @PathVariable LocalDate date,
        @PathVariable String entryId,
        @RequestBody UpdateEntryRequest body
    ) {
        String userId = currentUser.get().userId();
        FoodEntry entry = nutrition.updateEntry(
            userId,
            date,
            entryId,
            body != null ? body.meal() : null,
            body != null ? body.servingLabel() : null,
            body != null ? body.servingGrams() : null,
            body != null ? body.quantity() : null,
            body != null && body.macros() != null ? body.macros().toMacros() : null);
        return EntryResponse.from(entry);
    }

    @DeleteMapping("/{date}/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(
        @PathVariable LocalDate date,
        @PathVariable String entryId
    ) {
        String userId = currentUser.get().userId();
        nutrition.deleteEntry(userId, date, entryId);
        return ResponseEntity.noContent().build();
    }

    private static Macros macrosOf(NutritionDailyLog log) {
        return new Macros(
            log.caloriesKcal(),
            log.proteinGrams(),
            log.carbsGrams(),
            log.fatGrams(),
            log.fiberGrams(),
            log.sugarGrams());
    }

    // ----- DTOs ---------------------------------------------------------

    public record LogRequest(
        LocalDate date,
        Double proteinGrams,
        Double carbsGrams,
        Double fatGrams,
        Double fiberGrams,
        Double sugarGrams,
        Double caloriesKcal
    ) {}

    public record LogResponse(
        LocalDate date,
        Double proteinGrams,
        Double carbsGrams,
        Double fatGrams,
        Double fiberGrams,
        Double sugarGrams,
        Double caloriesKcal
    ) {
        public static LogResponse from(NutritionDailyLog log) {
            return new LogResponse(
                log.date(),
                log.proteinGrams(),
                log.carbsGrams(),
                log.fatGrams(),
                log.fiberGrams(),
                log.sugarGrams(),
                log.caloriesKcal());
        }
    }

    public record DayResponse(
        LocalDate date,
        MacrosDto totals,
        MacrosDto target,
        List<MealGroup> meals
    ) {}

    public record MealGroup(
        MealType meal,
        MacrosDto subtotal,
        List<EntryResponse> entries
    ) {}

    public record AddEntryRequest(
        MealType meal,
        String foodId,
        String foodName,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        MacrosDto macros,
        com.gte619n.healthfitness.core.nutrition.EntrySource source
    ) {}

    public record UpdateEntryRequest(
        MealType meal,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        MacrosDto macros
    ) {}
}
