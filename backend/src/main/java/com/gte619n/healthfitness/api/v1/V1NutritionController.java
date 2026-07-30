package com.gte619n.healthfitness.api.v1;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetRepository;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The nutrition surface (ADR-0020). Requires nutrition:read.
//   GET /v1/nutrition/entries?date= | from=&to=   — logged food entries
//   GET /v1/nutrition/days/{date}                 — daily macro totals vs target
@RestController
@RequestMapping("/v1/nutrition")
@PreAuthorize("hasAuthority('SCOPE_nutrition:read')")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Nutrition", description = "Logged food entries and daily macro totals vs "
    + "targets. Requires the `nutrition:read` scope.")
public class V1NutritionController {

    private static final int MAX_RANGE_DAYS = 92;

    private final CurrentUserProvider currentUser;
    private final NutritionService nutrition;
    private final MacroTargetRepository targets;

    public V1NutritionController(
        CurrentUserProvider currentUser,
        NutritionService nutrition,
        MacroTargetRepository targets
    ) {
        this.currentUser = currentUser;
        this.nutrition = nutrition;
        this.targets = targets;
    }

    @Operation(summary = "List food entries",
        description = "Logged food entries, newest first. Pass `date` for a single day, or a "
            + "`from`/`to` window (default last 7 days, max 92).")
    @GetMapping("/entries")
    public V1Page<EntryDto> entries(
        @Parameter(description = "Single day to fetch (YYYY-MM-DD). Overrides from/to.")
        @RequestParam(required = false) String date,
        @Parameter(description = "Inclusive lower bound date (YYYY-MM-DD) when `date` is omitted.")
        @RequestParam(required = false) String from,
        @Parameter(description = "Inclusive upper bound date (YYYY-MM-DD) when `date` is omitted.")
        @RequestParam(required = false) String to,
        @Parameter(description = "Opaque pagination cursor from a prior page's `nextCursor`.")
        @RequestParam(required = false) String cursor,
        @Parameter(description = "Max items per page (default 50, max 200).")
        @RequestParam(required = false) Integer limit,
        @Parameter(description = "ISO-8601 instant; return only entries updated at or after it.")
        @RequestParam(required = false) String updatedSince
    ) {
        String userId = currentUser.get().userId();
        Instant since = V1Params.instant(updatedSince);

        List<FoodEntry> entries = new ArrayList<>();
        LocalDate single = V1Params.date(date);
        if (single != null) {
            entries.addAll(nutrition.listEntries(userId, single));
        } else {
            V1Params.DateRange range = V1Params.dateRange(from, to, 7, MAX_RANGE_DAYS);
            for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
                entries.addAll(nutrition.listEntries(userId, d));
            }
        }
        List<FoodEntry> filtered = entries.stream()
            .filter(e -> since == null || (e.updatedAt() != null && !e.updatedAt().isBefore(since)))
            .toList();
        return V1Page.paginate(filtered, FoodEntry::updatedAt,
            e -> e.date() + "_" + e.entryId(), V1NutritionController::toEntry,
            cursor, V1Params.limit(limit));
    }

    @Operation(summary = "Get a day's nutrition totals",
        description = "Daily macro totals for the given date alongside the active macro target.")
    @GetMapping("/days/{date}")
    public DayResponse day(
        @Parameter(description = "The day to fetch (YYYY-MM-DD).") @PathVariable String date) {
        String userId = currentUser.get().userId();
        LocalDate day = V1Params.date(date);
        NutritionDailyLog log = nutrition.findByDate(userId, day)
            .orElseThrow(() -> new NoSuchElementException("no nutrition log for " + date));
        MacroDto target = targets.findActive(userId)
            .map(MacroTarget::macros).map(V1NutritionController::toMacro).orElse(null);
        return new DayResponse(
            log.date(),
            new MacroDto(log.caloriesKcal(), log.proteinGrams(), log.carbsGrams(),
                log.fatGrams(), log.fiberGrams(), log.sugarGrams()),
            target, log.updatedAt());
    }

    private static EntryDto toEntry(FoodEntry e) {
        return new EntryDto(
            e.entryId(), e.date(), name(e.meal()), e.foodId(), e.foodName(),
            e.servingLabel(), e.servingGrams(), e.quantity(), toMacro(e.macros()),
            name(e.source()), e.createdAt(), e.updatedAt());
    }

    private static MacroDto toMacro(Macros m) {
        if (m == null) return null;
        return new MacroDto(m.caloriesKcal(), m.proteinGrams(), m.carbsGrams(),
            m.fatGrams(), m.fiberGrams(), m.sugarGrams());
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    public record EntryDto(
        String id, LocalDate date, String meal, String foodId, String foodName,
        String servingLabel, Double servingGrams, Double quantity, MacroDto macros,
        String source, Instant createdAt, Instant updatedAt) {}

    public record MacroDto(
        Double caloriesKcal, Double proteinGrams, Double carbsGrams,
        Double fatGrams, Double fiberGrams, Double sugarGrams) {}

    public record DayResponse(
        LocalDate date, MacroDto totals, MacroDto target, Instant updatedAt) {}
}
