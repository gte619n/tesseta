package com.gte619n.healthfitness.core.goals.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.goals.Comparator;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserHealthSnapshotServiceTest {

    private static final String USER = "u1";

    @Test
    void rendersAllSectionsWhenDataPresent() {
        FakeMetrics metrics = new FakeMetrics();
        metrics.put(MetricKey.BODY_WEIGHT, 82.5);
        metrics.put(MetricKey.BLOOD_LDL, 95.0);
        metrics.put(MetricKey.VITALS_RESTING_HR, 58.0);

        FakeMedications meds = new FakeMedications();
        meds.add(new Medication(
            USER, "m1", "drug-1", null, MedicationStatus.ACTIVE,
            200, "mg", FrequencyConfig.daily(2), null, null, null, null,
            LocalDate.of(2026, 1, 1), null, null, null, null,
            List.of(), Instant.now(), Instant.now()));
        // A discontinued med must NOT appear (filter is status=ACTIVE).
        meds.add(new Medication(
            USER, "m2", "drug-2", null, MedicationStatus.DISCONTINUED,
            10, "mg", FrequencyConfig.daily(1), null, null, null, null,
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 1), null, null, null,
            List.of(), Instant.now(), Instant.now()));

        FakeDrugs drugs = new FakeDrugs();
        drugs.add(new Drug("drug-1", "Metformin", List.of(), null, null,
            "mg", List.of(), null, null, List.of(), null,
            Instant.now(), Instant.now(), null));

        FakeDexa dexa = new FakeDexa();
        dexa.add(new DexaScan(
            USER, "s1", LocalDate.of(2026, 5, 1), "Facility", null, null,
            180.0, 130.0, 45.0, 25.0,  // totalMass, lean, fat, bodyfat%
            null, null,
            null, null, null, null, null, null, null, null, null,
            -1.2, null, 1800, Instant.now(), Instant.now()));

        FakeBloodPanels panels = new FakeBloodPanels();
        panels.add(new BloodTestReport(
            USER, "r1", LocalDate.of(2026, 4, 15), "Quest", null, null,
            List.of(
                new ExtractedMarker("LDL", 95.0, "mg/dL", null, null, null),
                new ExtractedMarker("HDL", 60.0, "mg/dL", null, null, "H")),
            Instant.now(), Instant.now()));

        FakeDailyMetrics daily = new FakeDailyMetrics();
        daily.add(new DailyMetric(
            USER, LocalDate.now().minusDays(1), 8000, 58, 420, 65, 88,
            Instant.now(), Instant.now()));

        UserHealthSnapshotService svc = new UserHealthSnapshotService(
            metrics, meds, drugs, dexa, panels, daily);

        String out = svc.buildSnapshot(USER);

        assertTrue(out.contains("CURRENT USER HEALTH SNAPSHOT"), out);
        // Medications: active drug resolved to name + dose + frequency.
        assertTrue(out.contains("Metformin"), out);
        assertTrue(out.contains("200mg"), out);
        assertTrue(out.contains("2x daily"), out);
        // Discontinued med is filtered out.
        assertFalse(out.contains("drug-2"), out);
        // Body composition / DEXA.
        assertTrue(out.contains("Body fat: 25.0%"), out);
        assertTrue(out.contains("DEXA 2026-05-01"), out);
        // Blood panel markers.
        assertTrue(out.contains("Quest"), out);
        assertTrue(out.contains("LDL = 95.0 mg/dL"), out);
        assertTrue(out.contains("HDL = 60.0 mg/dL [H]"), out);
        // Vitals.
        assertTrue(out.contains("Resting HR: 58 bpm"), out);
        assertTrue(out.contains("Sleep score: 88"), out);
        // Registry metric values: every key present, available + no-data.
        assertTrue(out.contains("body.weight = 82.5"), out);
        assertTrue(out.contains("workouts.weeklyVolume = no data"), out);
        for (MetricKey k : MetricKey.values()) {
            assertTrue(out.contains(k.key() + " = "), "missing registry line for " + k.key() + "\n" + out);
        }
    }

    @Test
    void rendersGracefulFallbacksWhenEmpty() {
        UserHealthSnapshotService svc = new UserHealthSnapshotService(
            new FakeMetrics(), new FakeMedications(), new FakeDrugs(),
            new FakeDexa(), new FakeBloodPanels(), new FakeDailyMetrics());

        String out = svc.buildSnapshot(USER);

        assertTrue(out.contains("No active medications on record."), out);
        assertTrue(out.contains("No body composition or DEXA data on record."), out);
        assertTrue(out.contains("No blood panel or marker data on record."), out);
        assertTrue(out.contains("No vitals"), out);
        // Registry section still lists every key as "no data".
        for (MetricKey k : MetricKey.values()) {
            assertTrue(out.contains(k.key() + " = no data"), "missing no-data line for " + k.key() + "\n" + out);
        }
    }

    @Test
    void degradesWhenReposThrowUnsupported() {
        MedicationRepository throwingMeds = new MedicationRepository() {
            @Override public Optional<Medication> findById(String u, String id) { return Optional.empty(); }
            @Override public List<Medication> findByUser(String u) { return List.of(); }
            @Override public List<Medication> findByUserAndStatus(String u, MedicationStatus s) {
                throw new UnsupportedOperationException("stub");
            }
            @Override public List<Medication> findByProtocol(String u, String pid) { return List.of(); }
            @Override public void save(Medication m) {}
            @Override public void delete(String u, String id) {}
            @Override public List<Medication> findAllReferencingDrug(String drugId) { return List.of(); }
        };
        DailyMetricRepository throwingDaily = new DailyMetricRepository() {
            @Override public Optional<DailyMetric> findByDate(String u, LocalDate d) {
                throw new UnsupportedOperationException("stub");
            }
            @Override public List<DailyMetric> findByDateRange(String u, LocalDate from, LocalDate to) {
                throw new UnsupportedOperationException("stub");
            }
            @Override public void save(DailyMetric m) {}
        };

        UserHealthSnapshotService svc = new UserHealthSnapshotService(
            new FakeMetrics(), throwingMeds, new FakeDrugs(),
            new FakeDexa(), new FakeBloodPanels(), throwingDaily);

        String out = svc.buildSnapshot(USER);
        assertTrue(out.contains("No active medications on record."), out);
        assertTrue(out.contains("No vitals"), out);
    }

    // ---- in-test fakes ----

    private static final class FakeMetrics implements MetricResolver {
        private final Map<MetricKey, MetricValue> values = new HashMap<>();
        void put(MetricKey k, double v) { values.put(k, MetricValue.of(v, Instant.parse("2026-05-01T00:00:00Z"))); }
        @Override public MetricValue resolve(String userId, MetricKey key) {
            return values.getOrDefault(key, MetricValue.unavailable());
        }
        @Override public boolean sustainedHolds(String u, MetricKey k, Comparator c, double t, int w) { return false; }
        @Override public long countSince(String u, MetricKey k, Instant f) { return 0L; }
    }

    private static final class FakeMedications implements MedicationRepository {
        private final List<Medication> all = new ArrayList<>();
        void add(Medication m) { all.add(m); }
        @Override public Optional<Medication> findById(String u, String id) { return Optional.empty(); }
        @Override public List<Medication> findByUser(String u) { return all; }
        @Override public List<Medication> findByUserAndStatus(String u, MedicationStatus s) {
            return all.stream().filter(m -> m.status() == s).toList();
        }
        @Override public List<Medication> findByProtocol(String u, String pid) { return List.of(); }
        @Override public void save(Medication m) {}
        @Override public void delete(String u, String id) {}
        @Override public List<Medication> findAllReferencingDrug(String drugId) { return List.of(); }
    }

    private static final class FakeDrugs implements DrugRepository {
        private final Map<String, Drug> all = new HashMap<>();
        void add(Drug d) { all.put(d.drugId(), d); }
        @Override public Optional<Drug> findById(String drugId) { return Optional.ofNullable(all.get(drugId)); }
        @Override public List<Drug> findAll() { return List.copyOf(all.values()); }
        @Override public List<Drug> search(String query) { return List.of(); }
        @Override public Optional<Drug> findByNameIgnoreCase(String name) { return Optional.empty(); }
        @Override public void save(Drug d) {}
        @Override public void delete(String drugId) {}
    }

    private static final class FakeDexa implements DexaScanRepository {
        private final List<DexaScan> all = new ArrayList<>();
        void add(DexaScan s) { all.add(s); }
        @Override public void save(DexaScan s) {}
        @Override public Optional<DexaScan> findById(String u, String id) { return Optional.empty(); }
        @Override public List<DexaScan> findByUser(String u) { return all; }
        @Override public Optional<DexaScan> findByContentHash(String u, String h) { return Optional.empty(); }
        @Override public void delete(String u, String id) {}
    }

    private static final class FakeBloodPanels implements BloodTestReportRepository {
        private final List<BloodTestReport> all = new ArrayList<>();
        void add(BloodTestReport r) { all.add(r); }
        @Override public void save(BloodTestReport r) {}
        @Override public Optional<BloodTestReport> findById(String u, String id) { return Optional.empty(); }
        @Override public List<BloodTestReport> findByUser(String u) { return all; }
        @Override public Optional<BloodTestReport> findByContentHash(String u, String h) { return Optional.empty(); }
        @Override public void delete(String u, String id) {}
    }

    private static final class FakeDailyMetrics implements DailyMetricRepository {
        private final List<DailyMetric> all = new ArrayList<>();
        void add(DailyMetric m) { all.add(m); }
        @Override public Optional<DailyMetric> findByDate(String u, LocalDate d) { return Optional.empty(); }
        @Override public List<DailyMetric> findByDateRange(String u, LocalDate from, LocalDate to) { return all; }
        @Override public void save(DailyMetric m) {}
    }
}
