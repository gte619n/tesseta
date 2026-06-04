package com.gte619n.healthfitness.core.goals.chat;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.eval.MetricResolver;
import com.gte619n.healthfitness.core.goals.eval.MetricValue;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.FrequencyConfig;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.MedicationStatus;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Builds a compact, human-readable health snapshot for the Goals chat
 * planner so the model designs against the user's ACTUAL numbers instead
 * of planning blind.
 *
 * <p>The output is plain text — a handful of labelled sections the model
 * reads inline, appended to the static system prompt for one chat request.
 * It is intentionally NOT JSON or a PDF: the model only needs current
 * values, medications, body composition, blood markers, vitals, and the
 * exact value of every bindable registry metric. Empty sections degrade
 * to a one-line "no data" note rather than disappearing, so the model can
 * tell the difference between "no data" and "we didn't look".
 *
 * <p>Repo injection mirrors {@code FirestoreMetricResolver} — this is the
 * only Goals seam (besides the resolver) that reaches into other modules'
 * collections. Every read is defensive: a stub repo that throws
 * {@code UnsupportedOperationException} degrades to a "no data" line.
 */
@Service
public class UserHealthSnapshotService {

    private final MetricResolver metricResolver;
    private final MedicationRepository medications;
    private final DrugRepository drugs;
    private final DexaScanRepository dexaScans;
    private final BloodTestReportRepository bloodTestReports;
    private final DailyMetricRepository dailyMetrics;

    public UserHealthSnapshotService(
        MetricResolver metricResolver,
        MedicationRepository medications,
        DrugRepository drugs,
        DexaScanRepository dexaScans,
        BloodTestReportRepository bloodTestReports,
        DailyMetricRepository dailyMetrics
    ) {
        this.metricResolver = metricResolver;
        this.medications = medications;
        this.drugs = drugs;
        this.dexaScans = dexaScans;
        this.bloodTestReports = bloodTestReports;
        this.dailyMetrics = dailyMetrics;
    }

    /**
     * Render the snapshot for {@code userId} as plain text. Never returns
     * null; on a totally empty profile it still returns the header plus
     * graceful per-section fallbacks.
     */
    // Cache name must match CacheConfig.USER_HEALTH_SNAPSHOT in the app
    // module (core can't depend on app, so the literal is duplicated). ~60s
    // TTL there caps staleness; a chat burst reuses one ~24-metric build.
    @Cacheable(cacheNames = "userHealthSnapshot", key = "#userId")
    public String buildSnapshot(String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("CURRENT USER HEALTH SNAPSHOT (as of ")
            .append(LocalDate.now())
            .append("):\n");
        sb.append("Use these current values to ground the plan. Do not invent numbers; ")
            .append("if a metric says \"no data\", treat the Step as MANUAL or ask the user.\n");

        appendMedications(sb, userId);
        appendBodyComposition(sb, userId);
        appendBloodPanel(sb, userId);
        appendVitals(sb, userId);
        appendRegistryMetrics(sb, userId);

        return sb.toString();
    }

    // ---- medications ----

    private void appendMedications(StringBuilder sb, String userId) {
        sb.append("\nCURRENT MEDICATIONS:\n");
        List<Medication> active;
        try {
            active = medications.findByUserAndStatus(userId, MedicationStatus.ACTIVE);
        } catch (UnsupportedOperationException e) {
            active = List.of();
        }
        if (active == null || active.isEmpty()) {
            sb.append("  No active medications on record.\n");
            return;
        }
        for (Medication m : active) {
            if (m == null) continue;
            sb.append("  - ").append(medicationName(m));
            sb.append(": ").append(formatDose(m));
            String freq = formatFrequency(m.frequency());
            if (freq != null) {
                sb.append(", ").append(freq);
            }
            sb.append('\n');
        }
    }

    private String medicationName(Medication m) {
        if (m.customName() != null && !m.customName().isBlank()) {
            return m.customName();
        }
        if (m.drugId() != null && !m.drugId().isBlank()) {
            try {
                Optional<Drug> drug = drugs.findById(m.drugId());
                if (drug.isPresent() && drug.get().name() != null && !drug.get().name().isBlank()) {
                    return drug.get().name();
                }
            } catch (UnsupportedOperationException ignored) {
                // fall through to a generic label
            }
        }
        return "Medication";
    }

    private static String formatDose(Medication m) {
        String unit = m.unit() != null ? m.unit() : "";
        double dose = m.dose();
        String doseStr = dose == Math.rint(dose)
            ? String.valueOf((long) dose)
            : String.valueOf(dose);
        return (doseStr + unit).strip();
    }

    private static String formatFrequency(FrequencyConfig f) {
        if (f == null || f.type() == null) return null;
        return switch (f.type()) {
            case DAILY -> f.timesPerPeriod() != null ? f.timesPerPeriod() + "x daily" : "daily";
            case WEEKLY -> {
                if (f.specificDays() != null && !f.specificDays().isEmpty()) {
                    yield "weekly on " + f.specificDays();
                }
                yield f.timesPerPeriod() != null ? f.timesPerPeriod() + "x weekly" : "weekly";
            }
            case MONTHLY -> f.timesPerPeriod() != null ? f.timesPerPeriod() + "x monthly" : "monthly";
            case PRN -> "as needed (PRN)";
            case CYCLE -> "cycled";
        };
    }

    // ---- body composition / DEXA ----

    private void appendBodyComposition(StringBuilder sb, String userId) {
        sb.append("\nLATEST BODY COMPOSITION / DEXA:\n");
        DexaScan latest = null;
        try {
            List<DexaScan> scans = dexaScans.findByUser(userId); // newest-first
            if (scans != null) {
                for (DexaScan s : scans) {
                    if (s == null) continue;
                    if (latest == null
                        || (s.measuredOn() != null
                            && (latest.measuredOn() == null
                                || s.measuredOn().isAfter(latest.measuredOn())))) {
                        latest = s;
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // fall through to registry-derived values below
        }

        boolean wrote = false;
        if (latest != null) {
            String date = latest.measuredOn() != null ? latest.measuredOn().toString() : "unknown date";
            if (latest.totalMassLb() != null) {
                sb.append("  Total mass: ").append(latest.totalMassLb()).append(" lb (DEXA ").append(date).append(")\n");
                wrote = true;
            }
            if (latest.totalBodyFatPercent() != null) {
                sb.append("  Body fat: ").append(latest.totalBodyFatPercent()).append("% (DEXA ").append(date).append(")\n");
                wrote = true;
            }
            if (latest.leanTissueLb() != null) {
                sb.append("  Lean tissue: ").append(latest.leanTissueLb()).append(" lb (DEXA ").append(date).append(")\n");
                wrote = true;
            }
            if (latest.bmdTScore() != null) {
                sb.append("  Bone density T-score: ").append(latest.bmdTScore()).append(" (DEXA ").append(date).append(")\n");
                wrote = true;
            }
        }

        // Registry-resolved body metrics (BodyCompositionRepository-backed)
        // cover users who track weight/body-fat/lean-mass without a DEXA PDF.
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BODY_WEIGHT, "Weight", "kg");
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BODY_BODY_FAT_PCT, "Body fat", "%");
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BODY_LEAN_MASS, "Lean mass", "kg");

        if (!wrote) {
            sb.append("  No body composition or DEXA data on record.\n");
        }
    }

    private boolean appendMetricLineIfPresent(
        StringBuilder sb, String userId, MetricKey key, String label, String unit
    ) {
        MetricValue v = metricResolver.resolve(userId, key);
        if (v == null || !v.isAvailable()) return false;
        sb.append("  ").append(label).append(": ").append(v.value().get()).append(unit);
        if (v.asOf() != null) {
            sb.append(" (as of ").append(v.asOf()).append(')');
        }
        sb.append('\n');
        return true;
    }

    // ---- blood panel ----

    private void appendBloodPanel(StringBuilder sb, String userId) {
        sb.append("\nLATEST BLOOD PANEL:\n");
        BloodTestReport latest = null;
        try {
            List<BloodTestReport> panels = bloodTestReports.findByUser(userId); // newest-first
            if (panels != null) {
                for (BloodTestReport p : panels) {
                    if (p == null) continue;
                    if (latest == null
                        || (p.sampleDate() != null
                            && (latest.sampleDate() == null
                                || p.sampleDate().isAfter(latest.sampleDate())))) {
                        latest = p;
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // fall through to registry-derived markers
        }

        boolean wrote = false;
        if (latest != null && latest.markers() != null && !latest.markers().isEmpty()) {
            String date = latest.sampleDate() != null ? latest.sampleDate().toString() : "unknown date";
            String lab = latest.labSource() != null ? latest.labSource() : "lab";
            sb.append("  Panel from ").append(lab).append(" (").append(date).append("):\n");
            for (ExtractedMarker em : latest.markers()) {
                if (em == null || em.name() == null || em.value() == null) continue;
                sb.append("    ").append(em.name()).append(" = ").append(em.value());
                if (em.unit() != null && !em.unit().isBlank()) {
                    sb.append(' ').append(em.unit());
                }
                if (em.flag() != null && !em.flag().isBlank()) {
                    sb.append(" [").append(em.flag()).append(']');
                }
                sb.append('\n');
            }
            wrote = true;
        }

        // Standalone registry blood markers (BloodReading-backed) the resolver
        // exposes, in case the user logs them manually without a full panel.
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BLOOD_LDL, "LDL", " mg/dL");
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BLOOD_APOB, "ApoB", " mg/dL");
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BLOOD_HBA1C, "HbA1c", "%");
        wrote |= appendMetricLineIfPresent(sb, userId, MetricKey.BLOOD_HS_CRP, "hs-CRP", " mg/L");

        if (!wrote) {
            sb.append("  No blood panel or marker data on record.\n");
        }
    }

    // ---- vitals ----

    private void appendVitals(StringBuilder sb, String userId) {
        sb.append("\nLATEST VITALS:\n");
        DailyMetric latest = null;
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(7);
            List<DailyMetric> rows = dailyMetrics.findByDateRange(userId, from, to);
            if (rows != null) {
                for (DailyMetric m : rows) {
                    if (m == null) continue;
                    if (latest == null
                        || (m.date() != null
                            && (latest.date() == null || m.date().isAfter(latest.date())))) {
                        latest = m;
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // DailyMetric persistence is a stub today — degrade quietly.
        }

        boolean wrote = false;
        if (latest != null) {
            String date = latest.date() != null ? latest.date().toString() : "recent";
            if (latest.restingHeartRate() != null) {
                sb.append("  Resting HR: ").append(latest.restingHeartRate()).append(" bpm (").append(date).append(")\n");
                wrote = true;
            }
            if (latest.hrvMs() != null) {
                sb.append("  HRV: ").append(latest.hrvMs()).append(" ms (").append(date).append(")\n");
                wrote = true;
            }
            if (latest.sleepScore() != null) {
                sb.append("  Sleep score: ").append(latest.sleepScore()).append(" (").append(date).append(")\n");
                wrote = true;
            }
        }
        if (!wrote) {
            sb.append("  No vitals (resting HR / HRV / sleep) on record.\n");
        }
    }

    // ---- registry metric values ----

    private void appendRegistryMetrics(StringBuilder sb, String userId) {
        sb.append("\nCURRENT REGISTRY METRIC VALUES (the exact current value for every bindable metric):\n");
        for (MetricKey key : MetricKey.values()) {
            MetricValue v = metricResolver.resolve(userId, key);
            sb.append("  ").append(key.key()).append(" = ");
            if (v != null && v.isAvailable()) {
                sb.append(v.value().get());
            } else {
                sb.append("no data");
            }
            sb.append('\n');
        }
    }
}
