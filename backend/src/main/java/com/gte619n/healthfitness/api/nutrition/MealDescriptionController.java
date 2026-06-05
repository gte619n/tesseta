package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.MealDescriptionService;
import com.gte619n.healthfitness.core.nutrition.MealDescriptionService.MealResolution;
import com.gte619n.healthfitness.integrations.nutrition.NutritionExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Describe a meal" entry point: turns a free-text description into a resolved
 * meal — a previously-saved match when one exists, otherwise a newly created
 * meal (saved to the shared catalog with AI macros + a generating studio photo).
 *
 * <p>Unlike the photo {@code /capture} proposals, this DOES persist the resolved
 * meal (so it's findable next time); the client logs it onto a day via
 * {@code POST /api/me/nutrition/{date}/describe-meal}. Analysis failures map to
 * 422; an empty/foodless description maps to 422 as well.
 */
@RestController
@RequestMapping("/api/nutrition")
public class MealDescriptionController {

    private static final Logger log = LoggerFactory.getLogger(MealDescriptionController.class);

    private final CurrentUserProvider currentUser;
    private final MealDescriptionService describe;

    public MealDescriptionController(
        CurrentUserProvider currentUser, MealDescriptionService describe) {
        this.currentUser = currentUser;
        this.describe = describe;
    }

    @PostMapping("/describe")
    public DescribedMealResponse describe(@RequestBody DescribeRequest body) {
        if (body == null || body.description() == null || body.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required");
        }
        String userId = currentUser.get().userId();
        try {
            MealResolution resolution = describe.resolve(userId, body.description());
            return DescribedMealResponse.from(resolution);
        } catch (NutritionExtractionException | IllegalStateException e) {
            log.warn("Describe-meal resolution failed: {}", e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "could not understand the meal description");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    /** Request for {@code POST /api/nutrition/describe}. */
    public record DescribeRequest(String description) {}
}
