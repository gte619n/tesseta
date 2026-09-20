package com.gte619n.healthfitness.api.workoutstats;

import com.gte619n.healthfitness.api.support.RequestTimeZone;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutstats.E1rmHistory;
import com.gte619n.healthfitness.core.workoutstats.WorkoutStats;
import com.gte619n.healthfitness.core.workoutstats.WorkoutStatsService;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only stats surface for the web Overview dashboard (IMPL-WEB-WORKOUT-01
 * §5.2/§5.3): the consolidated summary and the lazily-fetched per-exercise e1RM
 * curve. Both are derived from the user's completed sessions and served behind a
 * short per-user cache. "Today" (hence the current-week boundary) is the
 * caller's local day from the {@code X-Timezone} header, defaulting to the
 * server zone.
 */
@RestController
@RequestMapping("/api/me/workout-stats")
public class WorkoutStatsController {

    private final CurrentUserProvider currentUser;
    private final WorkoutStatsService stats;

    public WorkoutStatsController(CurrentUserProvider currentUser, WorkoutStatsService stats) {
        this.currentUser = currentUser;
        this.stats = stats;
    }

    /** The full Overview bundle (streak, weekly series, heatmap, PRs, lift lists). */
    @GetMapping
    public WorkoutStats stats(
        @RequestParam(defaultValue = "" + WorkoutStatsService.DEFAULT_WEEKS) int weeks,
        @RequestHeader(value = RequestTimeZone.HEADER, required = false) String timezone
    ) {
        String userId = currentUser.get().userId();
        LocalDate today = LocalDate.now(RequestTimeZone.resolve(timezone));
        return stats.stats(userId, today, weeks);
    }

    /**
     * The estimated-1RM curve + current belief for one exercise, fetched lazily
     * when a lift is selected in the strength chart. Unknown ids and lifts with
     * no history return 200 with an empty {@code points} list (never 404).
     */
    @GetMapping("/e1rm-history")
    public E1rmHistory e1rmHistory(@RequestParam String exerciseId) {
        return stats.e1rmHistory(currentUser.get().userId(), exerciseId);
    }
}
