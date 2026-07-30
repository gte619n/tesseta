package com.gte619n.healthfitness.api.v1;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.dexa.DexaRegion;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The clinical / body-metrics surface (ADR-0020) — the most sensitive slice, so
// it is behind its own scope. Requires labs:read.
//   GET /v1/labs/blood?marker=&from=&to=        — blood marker readings
//   GET /v1/labs/dexa      /v1/labs/dexa/{id}   — DEXA scans
//   GET /v1/metrics/daily?from=&to=             — steps/RHR/sleep/HRV
//   GET /v1/metrics/body-composition?metric=    — weight / body fat / lean mass
@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAuthority('SCOPE_labs:read')")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Labs & metrics", description = "Blood readings, DEXA scans, body composition, "
    + "and daily health metrics — the most sensitive slice. Requires the `labs:read` scope.")
public class V1LabsController {

    private static final int MAX_RANGE_DAYS = 3660; // ~10 years of history

    private final CurrentUserProvider currentUser;
    private final BloodReadingRepository blood;
    private final DexaScanRepository dexa;
    private final BodyCompositionRepository bodyComposition;
    private final DailyMetricRepository dailyMetrics;

    public V1LabsController(
        CurrentUserProvider currentUser,
        BloodReadingRepository blood,
        DexaScanRepository dexa,
        BodyCompositionRepository bodyComposition,
        DailyMetricRepository dailyMetrics
    ) {
        this.currentUser = currentUser;
        this.blood = blood;
        this.dexa = dexa;
        this.bodyComposition = bodyComposition;
        this.dailyMetrics = dailyMetrics;
    }

    @Operation(summary = "List blood readings",
        description = "Blood marker readings, newest first, optionally filtered by marker/date.")
    @GetMapping("/labs/blood")
    public V1Page<BloodDto> bloodReadings(
        @Parameter(description = "Filter to a single blood marker (enum name, e.g. TESTOSTERONE).")
        @RequestParam(required = false) String marker,
        @Parameter(description = "Inclusive lower bound on sample date (YYYY-MM-DD).")
        @RequestParam(required = false) String from,
        @Parameter(description = "Inclusive upper bound on sample date (YYYY-MM-DD).")
        @RequestParam(required = false) String to,
        @Parameter(description = "Opaque pagination cursor from a prior page's `nextCursor`.")
        @RequestParam(required = false) String cursor,
        @Parameter(description = "Max items per page (default 50, max 200).")
        @RequestParam(required = false) Integer limit,
        @Parameter(description = "ISO-8601 instant; return only readings updated at or after it.")
        @RequestParam(required = false) String updatedSince
    ) {
        String userId = currentUser.get().userId();
        BloodMarker markerFilter = parseMarker(marker);
        LocalDate fromDate = V1Params.date(from);
        LocalDate toDate = V1Params.date(to);
        Instant since = V1Params.instant(updatedSince);
        List<BloodReading> readings = blood.findByUser(userId).stream()
            .filter(r -> markerFilter == null || r.marker() == markerFilter)
            .filter(r -> fromDate == null || (r.sampleDate() != null && !r.sampleDate().isBefore(fromDate)))
            .filter(r -> toDate == null || (r.sampleDate() != null && !r.sampleDate().isAfter(toDate)))
            .filter(r -> since == null || (r.updatedAt() != null && !r.updatedAt().isBefore(since)))
            .toList();
        return V1Page.paginate(readings,
            r -> r.sampleDate() == null ? Instant.EPOCH
                : r.sampleDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
            BloodReading::readingId, V1LabsController::toBlood, cursor, V1Params.limit(limit));
    }

    @Operation(summary = "List DEXA scans",
        description = "DEXA scan summaries, newest first.")
    @GetMapping("/labs/dexa")
    public V1Page<DexaSummary> dexaScans(
        @Parameter(description = "Opaque pagination cursor from a prior page's `nextCursor`.")
        @RequestParam(required = false) String cursor,
        @Parameter(description = "Max items per page (default 50, max 200).")
        @RequestParam(required = false) Integer limit,
        @Parameter(description = "ISO-8601 instant; return only scans updated at or after it.")
        @RequestParam(required = false) String updatedSince
    ) {
        String userId = currentUser.get().userId();
        Instant since = V1Params.instant(updatedSince);
        List<DexaScan> scans = dexa.findByUser(userId).stream()
            .filter(s -> since == null || (s.updatedAt() != null && !s.updatedAt().isBefore(since)))
            .toList();
        return V1Page.paginate(scans,
            s -> s.measuredOn() == null ? Instant.EPOCH
                : s.measuredOn().atStartOfDay(ZoneOffset.UTC).toInstant(),
            DexaScan::scanId, V1LabsController::toDexaSummary, cursor, V1Params.limit(limit));
    }

    @Operation(summary = "Get one DEXA scan",
        description = "A single DEXA scan with full per-region body-composition detail.")
    @GetMapping("/labs/dexa/{scanId}")
    public DexaDetail dexaScan(
        @Parameter(description = "DEXA scan id.") @PathVariable String scanId) {
        String userId = currentUser.get().userId();
        DexaScan scan = dexa.findById(userId, scanId)
            .orElseThrow(() -> new NoSuchElementException("dexa scan not found"));
        return toDexaDetail(scan);
    }

    @Operation(summary = "List daily metrics",
        description = "Daily steps / resting HR / sleep / HRV, newest first. Window defaults to "
            + "the last 90 days (max ~10 years).")
    @GetMapping("/metrics/daily")
    public V1Page<DailyMetricDto> dailyMetrics(
        @Parameter(description = "Inclusive lower bound date (YYYY-MM-DD).")
        @RequestParam(required = false) String from,
        @Parameter(description = "Inclusive upper bound date (YYYY-MM-DD).")
        @RequestParam(required = false) String to,
        @Parameter(description = "Opaque pagination cursor from a prior page's `nextCursor`.")
        @RequestParam(required = false) String cursor,
        @Parameter(description = "Max items per page (default 50, max 200).")
        @RequestParam(required = false) Integer limit
    ) {
        String userId = currentUser.get().userId();
        V1Params.DateRange range = V1Params.dateRange(from, to, 90, MAX_RANGE_DAYS);
        List<DailyMetric> metrics = dailyMetrics.findByDateRange(userId, range.from(), range.to());
        return V1Page.paginate(metrics,
            m -> m.date() == null ? Instant.EPOCH
                : m.date().atStartOfDay(ZoneOffset.UTC).toInstant(),
            m -> String.valueOf(m.date()), V1LabsController::toDailyMetric,
            cursor, V1Params.limit(limit));
    }

    @Operation(summary = "List body-composition measurements",
        description = "Weight / body-fat / lean-mass measurements, newest first, optionally "
            + "filtered by metric and a `from`/`to` instant window.")
    @GetMapping("/metrics/body-composition")
    public V1Page<BodyCompositionDto> bodyComposition(
        @Parameter(description = "Filter to a single metric (enum name, e.g. WEIGHT, BODY_FAT).")
        @RequestParam(required = false) String metric,
        @Parameter(description = "Inclusive lower bound sample time (ISO-8601 instant).")
        @RequestParam(required = false) String from,
        @Parameter(description = "Inclusive upper bound sample time (ISO-8601 instant).")
        @RequestParam(required = false) String to,
        @Parameter(description = "Opaque pagination cursor from a prior page's `nextCursor`.")
        @RequestParam(required = false) String cursor,
        @Parameter(description = "Max items per page (default 50, max 200).")
        @RequestParam(required = false) Integer limit
    ) {
        String userId = currentUser.get().userId();
        BodyCompositionMetric metricFilter = parseBodyMetric(metric);
        Instant fromT = V1Params.instant(from);
        Instant toT = V1Params.instant(to);
        List<BodyCompositionMeasurement> measurements = bodyComposition.findByUser(userId).stream()
            .filter(m -> metricFilter == null || m.metric() == metricFilter)
            .filter(m -> fromT == null || (m.sampleTime() != null && !m.sampleTime().isBefore(fromT)))
            .filter(m -> toT == null || (m.sampleTime() != null && !m.sampleTime().isAfter(toT)))
            .toList();
        return V1Page.paginate(measurements, BodyCompositionMeasurement::sampleTime,
            BodyCompositionMeasurement::recordId, V1LabsController::toBodyComposition,
            cursor, V1Params.limit(limit));
    }

    // --- parsing ---

    private static BloodMarker parseMarker(String marker) {
        if (marker == null || marker.isBlank()) return null;
        try {
            return BloodMarker.valueOf(marker.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown blood marker '" + marker + "'");
        }
    }

    private static BodyCompositionMetric parseBodyMetric(String metric) {
        if (metric == null || metric.isBlank()) return null;
        try {
            return BodyCompositionMetric.valueOf(metric.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown body-composition metric '" + metric + "'");
        }
    }

    // --- mappers ---

    private static BloodDto toBlood(BloodReading r) {
        return new BloodDto(r.readingId(), r.marker() == null ? null : r.marker().name(),
            r.value(), r.unit(), r.sampleDate(), r.labSource(), r.notes(),
            r.createdAt(), r.updatedAt());
    }

    private static DexaSummary toDexaSummary(DexaScan s) {
        return new DexaSummary(s.scanId(), s.measuredOn(), s.sourceFacility(),
            s.totalMassLb(), s.leanTissueLb(), s.fatTissueLb(), s.totalBodyFatPercent(),
            s.updatedAt());
    }

    private static DexaDetail toDexaDetail(DexaScan s) {
        return new DexaDetail(
            s.scanId(), s.measuredOn(), s.sourceFacility(),
            s.totalMassLb(), s.leanTissueLb(), s.fatTissueLb(), s.totalBodyFatPercent(),
            s.visceralFatLb(), s.androidGynoidRatio(),
            region(s.trunk()), region(s.android()), region(s.gynoid()),
            region(s.armsTotal()), region(s.armsRight()), region(s.armsLeft()),
            region(s.legsTotal()), region(s.legsRight()), region(s.legsLeft()),
            s.bmdTScore(), s.bmdZScore(), s.restingMetabolicRateKcal(), s.updatedAt());
    }

    private static RegionDto region(DexaRegion r) {
        if (r == null) return null;
        return new RegionDto(r.totalMassLb(), r.leanTissueLb(), r.fatTissueLb(), r.regionFatPercent());
    }

    private static DailyMetricDto toDailyMetric(DailyMetric m) {
        return new DailyMetricDto(m.date(), m.steps(), m.restingHeartRate(),
            m.sleepMinutes(), m.hrvMs(), m.sleepScore());
    }

    private static BodyCompositionDto toBodyComposition(BodyCompositionMeasurement m) {
        return new BodyCompositionDto(m.recordId(), m.metric() == null ? null : m.metric().name(),
            m.value(), m.sampleTime(), m.sourcePlatform(), m.recordingMethod());
    }

    // --- DTOs ---

    public record BloodDto(
        String id, String marker, double value, String unit, LocalDate sampleDate,
        String labSource, String notes, Instant createdAt, Instant updatedAt) {}

    public record DexaSummary(
        String id, LocalDate measuredOn, String sourceFacility, Double totalMassLb,
        Double leanTissueLb, Double fatTissueLb, Double totalBodyFatPercent, Instant updatedAt) {}

    public record DexaDetail(
        String id, LocalDate measuredOn, String sourceFacility,
        Double totalMassLb, Double leanTissueLb, Double fatTissueLb, Double totalBodyFatPercent,
        Double visceralFatLb, Double androidGynoidRatio,
        RegionDto trunk, RegionDto android, RegionDto gynoid,
        RegionDto armsTotal, RegionDto armsRight, RegionDto armsLeft,
        RegionDto legsTotal, RegionDto legsRight, RegionDto legsLeft,
        Double bmdTScore, Double bmdZScore, Integer restingMetabolicRateKcal, Instant updatedAt) {}

    public record RegionDto(
        Double totalMassLb, Double leanTissueLb, Double fatTissueLb, Double regionFatPercent) {}

    public record DailyMetricDto(
        LocalDate date, Integer steps, Integer restingHeartRate,
        Integer sleepMinutes, Integer hrvMs, Integer sleepScore) {}

    public record BodyCompositionDto(
        String id, String metric, double value, Instant sampleTime,
        String sourcePlatform, String recordingMethod) {}
}
