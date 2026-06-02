package com.gte619n.healthfitness.api.blood;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.blood.BloodReferenceRanges;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.sync.WriteResult;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    private final MetricChangedPublisher metricChangedPublisher;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public BloodController(
        CurrentUserProvider currentUser,
        BloodReadingRepository readings,
        MetricChangedPublisher metricChangedPublisher,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.readings = readings;
        this.metricChangedPublisher = metricChangedPublisher;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @GetMapping
    public List<BloodReadingResponse> list() {
        String userId = currentUser.get().userId();
        return readings.findByUser(userId).stream()
            .map(BloodReadingResponse::from)
            .toList();
    }

    @PostMapping
    public ResponseEntity<WriteResult<BloodReadingResponse>> create(@RequestBody CreateRequest body) {
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
        // Honor a client-minted id (offline-created UUID) when present, else
        // server-generate (IMPL-AND-20 D7). Idempotency-Key replays return the
        // current state of the originally-created reading instead of a dup.
        String readingId = syncWrite.resolveId(body.id());

        WriteResult<BloodReadingResponse> response = syncWrite.idempotentCreate(
            "bloodReadings:create",
            userId,
            () -> {
                // Authoritative post-write timestamp the client adopts as
                // lastUpdate (#11). The persistence layer stamps the matching
                // server updatedAt; this Instant is that same logical write time.
                Instant writtenAt = Instant.now();
                BloodReading reading = new BloodReading(
                    userId,
                    readingId,
                    marker,
                    body.value(),
                    unit,
                    body.sampleDate(),
                    body.labSource(),
                    body.notes(),
                    writtenAt,
                    writtenAt
                );
                readings.save(reading);
                // Publish after the save so a failed save never fires the event.
                MetricKey metricKey = MetricKey.fromBloodMarker(marker);
                if (metricKey != null) {
                    metricChangedPublisher.publish(userId, metricKey);
                }
                // Fan out to the user's other devices (origin suppressed, D18).
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "bloodReadings");
                return new SyncWriteContext.Created<>(
                    readingId,
                    WriteResult.of(BloodReadingResponse.from(reading), writtenAt));
            },
            id -> readings.findById(userId, id)
                .map(r -> WriteResult.of(BloodReadingResponse.from(r), r.updatedAt()))
        );
        return ResponseEntity.status(201).body(response);
    }

    @DeleteMapping("/{readingId}")
    public ResponseEntity<Void> delete(@PathVariable String readingId) {
        String userId = currentUser.get().userId();
        readings.delete(userId, readingId);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "bloodReadings");
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(
        String id,                  // optional client-minted UUID (D7); null ⇒ server-generated
        BloodMarker marker,
        Double value,
        String unit,
        LocalDate sampleDate,
        String labSource,
        String notes
    ) {}
}
