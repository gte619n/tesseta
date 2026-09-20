package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutstats.WorkoutStatsService.NeighborRef;
import java.util.List;

/**
 * One performed session for the web session-detail page (IMPL-WEB-WORKOUT-01
 * §5.5). Wraps the standard {@link ScheduledWorkoutResponse} (which already
 * carries programTitle + phaseTitle + the full block/exercise/logged-set tree)
 * and adds the two things a detail page needs beyond the calendar shape: which
 * logged sets were personal records, and the adjacent sessions to page to.
 *
 * @param session   the full scheduled/performed session
 * @param prSetKeys render-tree keys ({@code "blockId:orderIndex:setIndex"}) of
 *                  the sets that set a PR, for badging
 * @param prev      the immediately-older COMPLETED session, or null
 * @param next      the immediately-newer COMPLETED session, or null
 */
public record SessionDetailResponse(
    ScheduledWorkoutResponse session,
    List<String> prSetKeys,
    NeighborRef prev,
    NeighborRef next
) {}
