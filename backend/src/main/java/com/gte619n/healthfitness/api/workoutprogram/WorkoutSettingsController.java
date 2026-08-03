package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettings;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workout-preferences settings: currently the weekly streak target (how many
 * completed workouts a week needs to keep the consecutive-weeks streak alive).
 * The streak is computed on-device from the calendar; the backend only stores
 * this threshold so it syncs across the user's devices.
 */
@RestController
@RequestMapping("/api/me/workout-programs/settings")
public class WorkoutSettingsController {

    private final CurrentUserProvider currentUser;
    private final WorkoutSettingsService settings;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public WorkoutSettingsController(
        CurrentUserProvider currentUser,
        WorkoutSettingsService settings,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.settings = settings;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @GetMapping
    public WorkoutSettingsDto get() {
        return WorkoutSettingsDto.from(settings.get(currentUser.get().userId()));
    }

    /** PUT set-semantics (idempotent): replaces the whole settings document. */
    @PutMapping
    public WorkoutSettingsDto put(@RequestBody WorkoutSettingsDto body) {
        if (body == null || body.weeklyStreakTarget() == null) {
            throw new IllegalArgumentException("weeklyStreakTarget is required");
        }
        String userId = currentUser.get().userId();
        WorkoutSettings stored = settings.setWeeklyStreakTarget(userId, body.weeklyStreakTarget());
        // Wake the user's other devices so their landing streak recomputes.
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "workoutSettings");
        return WorkoutSettingsDto.from(stored);
    }

    public record WorkoutSettingsDto(Integer weeklyStreakTarget) {
        static WorkoutSettingsDto from(WorkoutSettings s) {
            return new WorkoutSettingsDto(s.weeklyStreakTarget());
        }
    }
}
