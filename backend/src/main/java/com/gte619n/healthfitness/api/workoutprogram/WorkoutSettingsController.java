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

    /**
     * Merge-update the settings: each field is applied only when present, so the
     * streak stepper and the preferences editor can each PUT just their own field
     * without clobbering the other. An omitted (null) field is left unchanged; a
     * blank {@code preferences} string clears the stored text.
     */
    @PutMapping
    public WorkoutSettingsDto put(@RequestBody WorkoutSettingsDto body) {
        if (body == null || (body.weeklyStreakTarget() == null && body.preferences() == null)) {
            throw new IllegalArgumentException(
                "at least one of weeklyStreakTarget or preferences is required");
        }
        String userId = currentUser.get().userId();
        WorkoutSettings stored = settings.get(userId);
        if (body.weeklyStreakTarget() != null) {
            stored = settings.setWeeklyStreakTarget(userId, body.weeklyStreakTarget());
        }
        if (body.preferences() != null) {
            stored = settings.setPreferences(userId, body.preferences());
        }
        // Wake the user's other devices so their landing streak + preferences refresh.
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "workoutSettings");
        return WorkoutSettingsDto.from(stored);
    }

    public record WorkoutSettingsDto(Integer weeklyStreakTarget, String preferences) {
        static WorkoutSettingsDto from(WorkoutSettings s) {
            return new WorkoutSettingsDto(s.weeklyStreakTarget(), s.preferences());
        }
    }
}
