package com.gte619n.healthfitness.api.bodycomposition;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/body-composition")
public class BodyCompositionController {

    private final BodyCompositionRepository measurements;
    private final CurrentUserProvider currentUser;

    public BodyCompositionController(
        BodyCompositionRepository measurements,
        CurrentUserProvider currentUser
    ) {
        this.measurements = measurements;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<BodyCompositionResponse> list(
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) BodyCompositionMetric metric
    ) {
        String userId = currentUser.get().userId();
        List<BodyCompositionMeasurement> results;
        if (metric != null && from != null && to != null) {
            results = measurements.findByUserAndRange(userId, metric, from, to);
        } else {
            results = measurements.findByUser(userId);
        }
        return results.stream().map(BodyCompositionResponse::from).toList();
    }
}
