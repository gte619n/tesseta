package com.gte619n.healthfitness.api.blood;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.blood.BloodReferenceRanges;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/blood")
public class BloodController {

    private final CurrentUserProvider currentUser;
    private final BloodReadingRepository readings;

    public BloodController(
        CurrentUserProvider currentUser,
        BloodReadingRepository readings
    ) {
        this.currentUser = currentUser;
        this.readings = readings;
    }

    @GetMapping
    public List<BloodReadingResponse> list() {
        String userId = currentUser.get().userId();
        return readings.findByUser(userId).stream()
            .map(BloodReadingResponse::from)
            .toList();
    }

    @PostMapping
    public ResponseEntity<BloodReadingResponse> create(@RequestBody CreateRequest body) {
        if (body.marker() == null) {
            throw new IllegalArgumentException("marker is required");
        }
        if (body.value() == null || body.value().isNaN()) {
            throw new IllegalArgumentException("value is required");
        }
        if (body.sampleDate() == null) {
            throw new IllegalArgumentException("sampleDate is required");
        }
        BloodMarker marker = body.marker();
        String unit = body.unit() != null && !body.unit().isBlank()
            ? body.unit()
            : BloodReferenceRanges.rangeFor(marker).unit();
        String userId = currentUser.get().userId();
        String readingId = UUID.randomUUID().toString();
        BloodReading reading = new BloodReading(
            userId,
            readingId,
            marker,
            body.value(),
            unit,
            body.sampleDate(),
            body.labSource(),
            body.notes(),
            null,
            null
        );
        readings.save(reading);
        return ResponseEntity.status(201).body(BloodReadingResponse.from(reading));
    }

    @DeleteMapping("/{readingId}")
    public ResponseEntity<Void> delete(@PathVariable String readingId) {
        String userId = currentUser.get().userId();
        readings.delete(userId, readingId);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(
        BloodMarker marker,
        Double value,
        String unit,
        LocalDate sampleDate,
        String labSource,
        String notes
    ) {}
}
