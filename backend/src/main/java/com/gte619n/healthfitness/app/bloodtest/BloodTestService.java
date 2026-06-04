package com.gte619n.healthfitness.app.bloodtest;

import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestDuplicateException;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestExtraction;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestExtractor;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestPdfStorage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Orchestrates a blood test upload: store the PDF in GCS first (so we
// always have the original if extraction fails later), then run Gemini,
// then persist the parsed report. Returns the saved BloodTestReport.
@Service
@ConditionalOnProperty(name = "app.bloodtest.enabled", havingValue = "true", matchIfMissing = true)
public class BloodTestService {

    private final BloodTestPdfStorage pdfStorage;
    private final BloodTestExtractor extractor;
    private final BloodTestReportRepository reports;
    private final MetricChangedPublisher metricChangedPublisher;

    public BloodTestService(
        BloodTestPdfStorage pdfStorage,
        BloodTestExtractor extractor,
        BloodTestReportRepository reports,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.pdfStorage = pdfStorage;
        this.extractor = extractor;
        this.reports = reports;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    public BloodTestReport upload(String userId, String fileName, byte[] pdfBytes) {
        return upload(userId, fileName, pdfBytes, phase -> {});
    }

    // Upload + extract + persist with phase callbacks for SSE clients.
    // Phase strings are stable and contract with the frontend:
    //   "uploading"  — PDF being written to GCS
    //   "extracting" — Gemini reading the PDF (the slow step)
    //   "saving"     — Firestore write
    public BloodTestReport upload(
        String userId,
        String fileName,
        byte[] pdfBytes,
        Consumer<String> onPhase
    ) {
        // Dedup BEFORE any expensive work. Same bytes for the same user
        // → reject up-front so we don't pay for storage or a Gemini call
        // on a duplicate.
        String contentHash = sha256Hex(pdfBytes);
        Optional<BloodTestReport> existing = reports.findByContentHash(userId, contentHash);
        if (existing.isPresent()) {
            BloodTestReport dup = existing.get();
            String when = dup.sampleDate() == null ? "earlier" : dup.sampleDate().toString();
            throw new BloodTestDuplicateException(
                "This blood test report has already been uploaded (sampled " + when + ").",
                dup.reportId());
        }

        onPhase.accept("uploading");
        String reportId = UUID.randomUUID().toString();
        String pdfPath = pdfStorage.upload(userId, reportId, pdfBytes);

        onPhase.accept("extracting");
        BloodTestExtraction extracted = extractor.extract(pdfBytes);

        onPhase.accept("saving");
        BloodTestReport report = new BloodTestReport(
            userId,
            reportId,
            extracted.sampleDate(),
            extracted.labSource(),
            pdfPath,
            contentHash,
            extracted.markers(),
            Instant.now(),
            Instant.now()
        );
        reports.save(report);
        // Publish after save; panel may contain multiple Goal-tracked markers.
        metricChangedPublisher.publishAll(userId, metricKeysForMarkers(report.markers()));
        return report;
    }

    public List<BloodTestReport> findAllByUserId(String userId) {
        return reports.findByUser(userId);
    }

    public Optional<BloodTestReport> findById(String userId, String reportId) {
        return reports.findById(userId, reportId);
    }

    public void delete(String userId, String reportId) {
        pdfStorage.delete(userId, reportId);
        reports.delete(userId, reportId);
    }

    // Whitelist of editable field paths. Marker fields use dotted paths
    // like "markers.0.value" or "markers.2.refRangeLow". Top-level fields
    // like sampleDate are also allowed. Anything not here is rejected
    // — keeps the API surface tight and avoids inadvertent edits to
    // immutable identity fields like reportId / userId / contentHash.
    private static final java.util.Set<String> EDITABLE_FIELD_NAMES = java.util.Set.of(
        "sampleDate", "labSource", "name", "value", "unit",
        "refRangeLow", "refRangeHigh", "flag"
    );

    public BloodTestReport updateField(String userId, String reportId, String path, Object value) {
        BloodTestReport existing = reports.findById(userId, reportId).orElseThrow(
            () -> new java.util.NoSuchElementException("Report not found"));

        if (!path.contains(".")) {
            // Top-level field
            if (!EDITABLE_FIELD_NAMES.contains(path)) {
                throw new IllegalArgumentException("Unknown or read-only field: " + path);
            }
            BloodTestReport updated = applyTopLevelPatch(existing, path, value);
            reports.save(updated);
            return updated;
        }

        // Dotted path: markers.N.field
        String[] parts = path.split("\\.", 3);
        if (parts.length != 3 || !"markers".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        int index;
        try {
            index = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid marker index in path: " + path);
        }

        String field = parts[2];
        if (!EDITABLE_FIELD_NAMES.contains(field)) {
            throw new IllegalArgumentException("Unknown or read-only field: " + field);
        }

        List<ExtractedMarker> markers = existing.markers() == null
            ? new ArrayList<>()
            : new ArrayList<>(existing.markers());

        if (index < 0 || index >= markers.size()) {
            throw new IllegalArgumentException("Marker index out of bounds: " + index);
        }

        ExtractedMarker original = markers.get(index);
        ExtractedMarker patched = patchMarker(original, field, value);
        markers.set(index, patched);

        BloodTestReport updated = new BloodTestReport(
            existing.userId(),
            existing.reportId(),
            existing.sampleDate(),
            existing.labSource(),
            existing.pdfStoragePath(),
            existing.contentHash(),
            markers,
            existing.createdAt(),
            Instant.now()
        );
        reports.save(updated);
        // Publish after save; the patched marker (and any siblings in the panel)
        // may satisfy a Goals Step bound to that blood metric.
        metricChangedPublisher.publishAll(userId, metricKeysForMarkers(updated.markers()));
        return updated;
    }

    private static BloodTestReport applyTopLevelPatch(
        BloodTestReport r,
        String field,
        Object value
    ) {
        return switch (field) {
            case "sampleDate" -> new BloodTestReport(
                r.userId(), r.reportId(),
                value == null ? null : java.time.LocalDate.parse(value.toString()),
                r.labSource(), r.pdfStoragePath(), r.contentHash(), r.markers(),
                r.createdAt(), Instant.now()
            );
            case "labSource" -> new BloodTestReport(
                r.userId(), r.reportId(), r.sampleDate(),
                value == null ? null : value.toString(),
                r.pdfStoragePath(), r.contentHash(), r.markers(),
                r.createdAt(), Instant.now()
            );
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        };
    }

    private static ExtractedMarker patchMarker(ExtractedMarker m, String field, Object value) {
        return switch (field) {
            case "name" -> new ExtractedMarker(
                value == null ? null : value.toString(),
                m.value(), m.unit(), m.refRangeLow(), m.refRangeHigh(), m.flag()
            );
            case "value" -> new ExtractedMarker(
                m.name(), asDouble(value), m.unit(), m.refRangeLow(), m.refRangeHigh(), m.flag()
            );
            case "unit" -> new ExtractedMarker(
                m.name(), m.value(), value == null ? null : value.toString(),
                m.refRangeLow(), m.refRangeHigh(), m.flag()
            );
            case "refRangeLow" -> new ExtractedMarker(
                m.name(), m.value(), m.unit(), asDouble(value), m.refRangeHigh(), m.flag()
            );
            case "refRangeHigh" -> new ExtractedMarker(
                m.name(), m.value(), m.unit(), m.refRangeLow(), asDouble(value), m.flag()
            );
            case "flag" -> new ExtractedMarker(
                m.name(), m.value(), m.unit(), m.refRangeLow(), m.refRangeHigh(),
                value == null ? null : value.toString()
            );
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        };
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number: " + s);
            }
        }
        throw new IllegalArgumentException("Cannot convert to Double: " + value.getClass());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convert a list of {@link ExtractedMarker}s to the set of
     * {@link MetricKey}s that Goals cares about.
     *
     * Marker names arrive as free-form strings from Gemini (e.g. "LDL",
     * "Apo-B", "HbA1c"). We normalize to upper-case with hyphens/
     * underscores stripped, then try to parse as a {@link BloodMarker}
     * enum constant before mapping via {@link MetricKey#fromBloodMarker}.
     *
     * Unknown/unmapped markers produce no entry in the returned set —
     * they never cause a publish.
     */
    private static Set<MetricKey> metricKeysForMarkers(List<ExtractedMarker> markers) {
        if (markers == null || markers.isEmpty()) return Set.of();
        Set<MetricKey> keys = new LinkedHashSet<>();
        for (ExtractedMarker em : markers) {
            if (em == null || em.name() == null || em.value() == null) continue;
            String normalized = em.name().toUpperCase(java.util.Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
            // Try direct enum name match.
            BloodMarker marker;
            try {
                // BloodMarker uses canonical names: LDL, APO_B, HBA1C, HS_CRP, etc.
                // Normalize APOB -> APO_B, HSCRP -> HS_CRP for Gemini variants.
                String enumName = toEnumName(normalized);
                marker = BloodMarker.valueOf(enumName);
            } catch (IllegalArgumentException ignored) {
                continue; // not a known marker
            }
            MetricKey key = MetricKey.fromBloodMarker(marker);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Map Gemini-normalized marker names to {@link BloodMarker} enum
     * constant names. Only the cases that differ from the enum name need
     * explicit entries here.
     */
    private static String toEnumName(String normalized) {
        return switch (normalized) {
            case "APOB"  -> "APO_B";
            case "HSCRP" -> "HS_CRP";
            default      -> normalized;
        };
    }
}
