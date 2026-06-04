package com.gte619n.healthfitness.api.metric;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Day-grained activity / vitals metrics (steps, resting HR, HRV, sleep)
// ingested from Google Health. Defaults to the trailing 90 days when no
// range is supplied, matching the dashboard's default window.
@RestController
@RequestMapping("/api/me/daily-metrics")
public class DailyMetricController {

    private static final int DEFAULT_WINDOW_DAYS = 90;

    private final DailyMetricRepository dailyMetrics;
    private final CurrentUserProvider currentUser;

    public DailyMetricController(DailyMetricRepository dailyMetrics, CurrentUserProvider currentUser) {
        this.dailyMetrics = dailyMetrics;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<DailyMetricResponse> list(
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        String userId = currentUser.get().userId();
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        return dailyMetrics.findByDateRange(userId, start, end).stream()
            .map(DailyMetricResponse::from)
            .toList();
    }

    public record DailyMetricResponse(
        LocalDate date,
        Integer steps,
        Integer restingHeartRate,
        Integer sleepMinutes,
        Integer hrvMs,
        Integer sleepScore
    ) {
        static DailyMetricResponse from(DailyMetric m) {
            return new DailyMetricResponse(
                m.date(),
                m.steps(),
                m.restingHeartRate(),
                m.sleepMinutes(),
                m.hrvMs(),
                m.sleepScore()
            );
        }
    }
}
