package com.gte619n.healthfitness.core.nutrition.jobs;

import com.gte619n.healthfitness.core.nutrition.FoodEntryImageService;
import com.gte619n.healthfitness.core.nutrition.FoodImageService;
import com.gte619n.healthfitness.core.nutrition.MealCaptureService;
import com.gte619n.healthfitness.core.nutrition.MealDescriptionService;
import com.gte619n.healthfitness.core.nutrition.SavedMealImageService;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Executes a {@link NutritionJob} by routing it to the owning service's
 * {@code *OrThrow} work method (Tier 3). Shared by both queue adapters: the local
 * executor calls {@link #dispatch} directly, and the Cloud Tasks HTTP handler
 * calls it per delivery.
 *
 * <p>{@link #dispatch} throws {@link NutritionJobException} when the work did not
 * complete, so the caller can retry; {@link #markFailed} records the terminal
 * failure once retries are exhausted. Services are injected via
 * {@link ObjectProvider} so the dispatcher tolerates a core-only / image-disabled
 * context (the job becomes a no-op rather than a wiring failure), matching the
 * seams the services themselves use for their generator/store ports.
 */
@Component
public class NutritionJobDispatcher {

    private final ObjectProvider<FoodImageService> foodImages;
    private final ObjectProvider<FoodEntryImageService> entryImages;
    private final ObjectProvider<SavedMealImageService> savedMealImages;
    private final ObjectProvider<MealCaptureService> mealCapture;
    private final ObjectProvider<MealDescriptionService> mealDescription;

    public NutritionJobDispatcher(
        ObjectProvider<FoodImageService> foodImages,
        ObjectProvider<FoodEntryImageService> entryImages,
        ObjectProvider<SavedMealImageService> savedMealImages,
        ObjectProvider<MealCaptureService> mealCapture,
        ObjectProvider<MealDescriptionService> mealDescription
    ) {
        this.foodImages = foodImages;
        this.entryImages = entryImages;
        this.savedMealImages = savedMealImages;
        this.mealCapture = mealCapture;
        this.mealDescription = mealDescription;
    }

    /**
     * Run the job's work. Throws {@link NutritionJobException} if it did not
     * complete (the caller decides whether to retry). Idempotent: each work
     * method early-returns when its target is already in a terminal state, so a
     * redelivered job is a cheap no-op.
     */
    public void dispatch(NutritionJob job) {
        if (job == null || job.type() == null) {
            return;
        }
        switch (job.type()) {
            case FOOD_IMAGE -> {
                FoodImageService s = foodImages.getIfAvailable();
                if (s != null) {
                    s.generateOrThrow(job.id(), job.ref());
                }
            }
            case ENTRY_IMAGE -> {
                FoodEntryImageService s = entryImages.getIfAvailable();
                if (s != null) {
                    s.generateOrThrow(job.userId(), date(job), job.id(), job.name(), job.ref());
                }
            }
            case SAVED_MEAL_IMAGE -> {
                SavedMealImageService s = savedMealImages.getIfAvailable();
                if (s != null) {
                    s.generateOrThrow(job.id());
                }
            }
            case MEAL_ANALYSIS -> {
                MealCaptureService s = mealCapture.getIfAvailable();
                if (s != null) {
                    s.analyzeFromRefOrThrow(job.userId(), date(job), job.id(), job.ref(), job.mime());
                }
            }
            case DESCRIPTION_ANALYSIS -> {
                MealDescriptionService s = mealDescription.getIfAvailable();
                if (s != null) {
                    s.resolveAndFinalizeOrThrow(job.userId(), date(job), job.id(), job.name());
                }
            }
            default -> { /* unknown type: ignore so an old queued job can't wedge the handler */ }
        }
    }

    /**
     * Record the terminal failure of a job whose retries are exhausted, so the
     * placeholder/image row stops spinning and the user sees a failed state they
     * can retry or delete.
     */
    public void markFailed(NutritionJob job) {
        if (job == null || job.type() == null) {
            return;
        }
        switch (job.type()) {
            case FOOD_IMAGE -> {
                FoodImageService s = foodImages.getIfAvailable();
                if (s != null) {
                    s.markFailed(job.id());
                }
            }
            case ENTRY_IMAGE -> {
                FoodEntryImageService s = entryImages.getIfAvailable();
                if (s != null) {
                    s.markFailed(job.userId(), date(job), job.id());
                }
            }
            case SAVED_MEAL_IMAGE -> {
                SavedMealImageService s = savedMealImages.getIfAvailable();
                if (s != null) {
                    s.markFailed(job.id());
                }
            }
            case MEAL_ANALYSIS -> {
                MealCaptureService s = mealCapture.getIfAvailable();
                if (s != null) {
                    s.markFailed(job.userId(), date(job), job.id());
                }
            }
            case DESCRIPTION_ANALYSIS -> {
                MealDescriptionService s = mealDescription.getIfAvailable();
                if (s != null) {
                    s.markFailed(job.userId(), date(job), job.id());
                }
            }
            default -> { /* unknown type: nothing to fail */ }
        }
    }

    private static LocalDate date(NutritionJob job) {
        return job.date() == null ? null : LocalDate.parse(job.date());
    }
}
