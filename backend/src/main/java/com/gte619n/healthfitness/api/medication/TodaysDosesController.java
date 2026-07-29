package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.api.support.RequestTimeZone;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.medication.TodaysDose;
import com.gte619n.healthfitness.core.medication.TodaysDosesService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for today's scheduled doses.
 * Endpoint: /api/me/medications/today
 *
 * <p>The scheduling rule (frequency / day-of-week / cycle / taken status) lives
 * in {@link TodaysDosesService}, shared with the third-party GET /v1/doses
 * (ADR-0020, decision D10). This controller only adapts it to the first-party
 * wire shape.
 */
@RestController
@RequestMapping("/api/me/medications/today")
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class TodaysDosesController {

    private final CurrentUserProvider currentUser;
    private final TodaysDosesService todaysDoses;

    public TodaysDosesController(CurrentUserProvider currentUser, TodaysDosesService todaysDoses) {
        this.currentUser = currentUser;
        this.todaysDoses = todaysDoses;
    }

    /**
     * Get all scheduled doses for "today" with their taken status.
     *
     * <p>"Today" is the caller's local date: an explicit {@code ?date=} wins
     * (the phone passes it), otherwise it's derived from the {@code X-Timezone}
     * header (the web app, whose fetch is server-side, can't pass a date). Either
     * way the checklist resets at the user's local midnight, not the server's.
     * Falls back to the server date when neither is present.
     */
    @GetMapping
    public List<TodaysDoseResponse> list(
        @RequestParam(required = false) LocalDate date,
        @RequestHeader(value = RequestTimeZone.HEADER, required = false) String timezone
    ) {
        String userId = currentUser.get().userId();
        LocalDate today = date != null ? date : LocalDate.now(RequestTimeZone.resolve(timezone));
        return todaysDoses.forDate(userId, today).stream()
            .map(d -> new TodaysDoseResponse(
                d.medicationId(), d.drugName(), d.imageUrl(), d.window(),
                d.dose(), d.unit(), d.taken(), d.takenAt()))
            .toList();
    }

    // Response DTO

    public record TodaysDoseResponse(
        String medicationId,
        String drugName,
        String imageUrl,
        TimeWindow window,
        double dose,
        String unit,
        boolean taken,
        Instant takenAt
    ) {}
}
