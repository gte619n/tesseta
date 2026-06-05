package com.gte619n.healthfitness.api.dashboard;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard "Recent" feed: the user's latest activity merged across workouts,
 * weigh-ins, sleep, logged food, and medication doses — newest first. The
 * cross-source merge/sort/cap lives in {@link RecentActivityService} so the web
 * and Android clients share one feed instead of each re-assembling it.
 */
@RestController
@RequestMapping("/api/me/recent-activity")
public class RecentActivityController {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final CurrentUserProvider currentUser;
    private final RecentActivityService service;

    public RecentActivityController(CurrentUserProvider currentUser, RecentActivityService service) {
        this.currentUser = currentUser;
        this.service = service;
    }

    @GetMapping
    public List<RecentActivityResponse> recent(
        @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return service.recent(currentUser.get().userId(), capped);
    }
}
