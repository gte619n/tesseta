package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.workoutprogram.CompletedSessionSource;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Feeds completed ad-hoc runs into the shared read paths (weekly stats + e1RM),
 * so an ad-hoc workout counts toward history/streaks/strength exactly like a
 * program session (IMPL-ADHOC-01 D6/AD-06).
 */
@Component
public class AdHocCompletedSessionSource implements CompletedSessionSource {

    private final AdHocSessionRepository sessions;

    public AdHocCompletedSessionSource(AdHocSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public List<PerformedSession> completedSessions(String userId, LocalDate from, LocalDate to) {
        List<PerformedSession> out = new ArrayList<>();
        for (ScheduledWorkout sw : sessions.findCompletedByUser(userId, from, to)) {
            if (sw.session() == null || sw.date() == null) {
                continue;
            }
            out.add(new PerformedSession(sw.date(), sw.session(), sw.programId()));
        }
        return out;
    }
}
