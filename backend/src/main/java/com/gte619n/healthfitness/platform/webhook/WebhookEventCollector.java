package com.gte619n.healthfitness.platform.webhook;

import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Detects webhook-worthy changes for a user in a time window (ADR-0020, D19), by
// reusing the same repositories the /v1 read API serves — so events never expose
// anything a /v1 read wouldn't. Only event types the subscription requested AND
// the client holds the scope for are collected.
//
// Change detection uses each entity's natural change instant: updatedAt for
// medications/labs/dexa/metrics/nutrition-days, DoseLog.takenAt for adherence,
// and ScheduledWorkout.completedAt for completed sessions.
@Component
@ConditionalOnProperty(name = "app.platform.webhooks-enabled", havingValue = "true")
public class WebhookEventCollector {

    private final MedicationRepository medications;
    private final AdherenceRepository adherence;
    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;
    private final NutritionDailyLogRepository nutritionDays;
    private final BloodReadingRepository blood;
    private final DexaScanRepository dexa;
    private final DailyMetricRepository dailyMetrics;

    public WebhookEventCollector(
        MedicationRepository medications,
        AdherenceRepository adherence,
        WorkoutProgramRepository programs,
        ScheduledWorkoutRepository scheduled,
        NutritionDailyLogRepository nutritionDays,
        BloodReadingRepository blood,
        DexaScanRepository dexa,
        DailyMetricRepository dailyMetrics
    ) {
        this.medications = medications;
        this.adherence = adherence;
        this.programs = programs;
        this.scheduled = scheduled;
        this.nutritionDays = nutritionDays;
        this.blood = blood;
        this.dexa = dexa;
        this.dailyMetrics = dailyMetrics;
    }

    public List<WebhookEvent> collect(
        String userId, Set<String> grantedScopes, Set<String> subscribedEventTypes,
        Instant since, Instant until
    ) {
        Set<WebhookEventType> eligible =
            WebhookEventType.eligible(subscribedEventTypes, grantedScopes);
        List<WebhookEvent> events = new ArrayList<>();
        if (eligible.isEmpty()) {
            return events;
        }
        LocalDate fromDate = since.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate toDate = until.atZone(ZoneOffset.UTC).toLocalDate();

        if (eligible.contains(WebhookEventType.MEDICATION_CHANGED)) {
            for (var m : medications.findByUser(userId)) {
                if (inRange(m.updatedAt(), since, until)) {
                    events.add(new WebhookEvent("medication.changed", userId, m.updatedAt(),
                        "medication", m.medicationId(), "/v1/medications/" + m.medicationId()));
                }
            }
        }
        if (eligible.contains(WebhookEventType.DOSE_LOGGED)) {
            for (var log : adherence.findByUserAndDateRange(userId, fromDate, toDate)) {
                if (log.doses() == null) continue;
                for (var dose : log.doses()) {
                    if (inRange(dose.takenAt(), since, until)) {
                        events.add(new WebhookEvent("dose.logged", userId, dose.takenAt(),
                            "adherence", log.medicationId() + ":" + log.date(),
                            "/v1/adherence?from=" + log.date() + "&to=" + log.date()));
                    }
                }
            }
        }
        if (eligible.contains(WebhookEventType.WORKOUT_COMPLETED)) {
            for (var program : programs.findByUser(userId)) {
                for (var s : scheduled.findByStatus(userId, program.programId(),
                    ScheduledStatus.COMPLETED)) {
                    if (inRange(s.completedAt(), since, until)) {
                        events.add(new WebhookEvent("workout.completed", userId, s.completedAt(),
                            "workout", s.scheduledId(),
                            "/v1/workouts/" + program.programId() + "/" + s.scheduledId()));
                    }
                }
            }
        }
        if (eligible.contains(WebhookEventType.NUTRITION_DAY_UPDATED)) {
            for (var log : nutritionDays.findByDateRange(userId, fromDate, toDate)) {
                if (inRange(log.updatedAt(), since, until)) {
                    events.add(new WebhookEvent("nutrition.day.updated", userId, log.updatedAt(),
                        "nutrition-day", String.valueOf(log.date()),
                        "/v1/nutrition/days/" + log.date()));
                }
            }
        }
        if (eligible.contains(WebhookEventType.LAB_ADDED)) {
            for (var r : blood.findByUser(userId)) {
                if (inRange(r.updatedAt(), since, until)) {
                    events.add(new WebhookEvent("lab.added", userId, r.updatedAt(),
                        "blood-reading", r.readingId(), "/v1/labs/blood"));
                }
            }
        }
        if (eligible.contains(WebhookEventType.DEXA_ADDED)) {
            for (var s : dexa.findByUser(userId)) {
                if (inRange(s.updatedAt(), since, until)) {
                    events.add(new WebhookEvent("dexa.added", userId, s.updatedAt(),
                        "dexa-scan", s.scanId(), "/v1/labs/dexa/" + s.scanId()));
                }
            }
        }
        if (eligible.contains(WebhookEventType.DAILY_METRIC_UPDATED)) {
            for (var m : dailyMetrics.findByDateRange(userId, fromDate, toDate)) {
                if (inRange(m.updatedAt(), since, until)) {
                    events.add(new WebhookEvent("daily-metric.updated", userId, m.updatedAt(),
                        "daily-metric", String.valueOf(m.date()),
                        "/v1/metrics/daily?from=" + m.date() + "&to=" + m.date()));
                }
            }
        }

        events.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
        return events;
    }

    private static boolean inRange(Instant t, Instant since, Instant until) {
        return t != null && t.isAfter(since) && !t.isAfter(until);
    }
}
