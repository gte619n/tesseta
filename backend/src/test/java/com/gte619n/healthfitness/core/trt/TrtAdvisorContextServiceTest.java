package com.gte619n.healthfitness.core.trt;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.medication.DosagePeriod;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.FrequencyConfig;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.MedicationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrtAdvisorContextServiceTest {

    private static final String USER = "user-1";
    private static final LocalDate RECENT = LocalDate.of(2026, 5, 1);
    private static final LocalDate OLDER = LocalDate.of(2026, 1, 1);

    // ---- (a) hematocrit > 54 always yields a DANGER flag --------------------------------

    @Test
    @DisplayName("hematocrit > 54% always produces a DANGER flag")
    void hematocritOver54IsDanger() {
        BloodTestReport report = report(RECENT, List.of(
            marker("Hematocrit", 55.2, "%", 38.0, 50.0, "H")));
        TrtAdvisorContextService svc = service(List.of(report), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.dangerFlags())
            .anySatisfy(f -> {
                assertThat(f.severity()).isEqualTo(Severity.DANGER);
                assertThat(f.marker()).isEqualTo("hematocrit");
                assertThat(f.message()).contains("54%").contains("polycythemia");
            });
    }

    @Test
    @DisplayName("hematocrit reported as a fraction > 0.54 still fires DANGER")
    void hematocritFractionOver054IsDanger() {
        BloodTestReport report = report(RECENT, List.of(
            marker("HCT", 0.56, "L/L", 0.38, 0.50, "H")));
        TrtAdvisorContextService svc = service(List.of(report), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.dangerFlags())
            .anySatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.DANGER));
    }

    @Test
    @DisplayName("hematocrit in the 52-54 band is a WATCH status with a WARNING flag")
    void hematocrit53IsWatch() {
        BloodTestReport report = report(RECENT, List.of(
            marker("Hematocrit", 53.0, "%", 38.0, 50.0, "H")));
        TrtAdvisorContextService svc = service(List.of(report), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.markers())
            .filteredOn(m -> m.name().equals("hematocrit"))
            .singleElement()
            .satisfies(m -> assertThat(m.status()).isEqualTo(MarkerStatus.WATCH));
        assertThat(ctx.dangerFlags())
            .anySatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
    }

    // ---- (b) markers reflect actual latest values + status vs range ---------------------

    @Test
    @DisplayName("markers reflect the user's actual latest values and status vs range")
    void markersReflectLatestValuesAndStatus() {
        BloodTestReport report = report(RECENT, List.of(
            marker("Total Testosterone", 525.0, "ng/dL", 264.0, 916.0, null),
            marker("Estradiol", 28.0, "pg/mL", 8.0, 35.0, null),
            marker("LDL", 165.0, "mg/dL", 0.0, 100.0, "H")));
        TrtAdvisorContextService svc = service(List.of(report), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.markers())
            .filteredOn(m -> m.name().equals("totalTestosterone"))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.value()).isEqualTo(525.0);
                assertThat(m.unit()).isEqualTo("ng/dL");
                assertThat(m.refLow()).isEqualTo(264.0);
                assertThat(m.refHigh()).isEqualTo(916.0);
                assertThat(m.sampleDate()).isEqualTo(RECENT);
                assertThat(m.status()).isEqualTo(MarkerStatus.IN_RANGE);
            });
        assertThat(ctx.markers())
            .filteredOn(m -> m.name().equals("ldl"))
            .singleElement()
            .satisfies(m -> assertThat(m.status()).isEqualTo(MarkerStatus.HIGH));
    }

    // ---- (c) isOnTrt true for testosterone cypionate, false otherwise -------------------

    @Test
    @DisplayName("isOnTrt is true for an active testosterone-cypionate medication")
    void isOnTrtTrueForTestosteroneCypionate() {
        Medication med = trtMedViaDrug("drug-test-cyp");
        Drug drug = drug("drug-test-cyp", "Testosterone Cypionate", List.of("Depo-Testosterone"));
        TrtAdvisorContextService svc = service(List.of(), List.of(med), List.of(drug));

        assertThat(svc.isOnTrt(USER)).isTrue();
    }

    @Test
    @DisplayName("isOnTrt is true via customName when no drug catalog entry exists")
    void isOnTrtTrueViaCustomName() {
        Medication med = customNameMed("Test Cyp 200mg");
        TrtAdvisorContextService svc = service(List.of(), List.of(med));

        assertThat(svc.isOnTrt(USER)).isTrue();
    }

    @Test
    @DisplayName("isOnTrt is false for an unrelated medication")
    void isOnTrtFalseForUnrelatedMed() {
        Medication med = customNameMed("Atorvastatin 20mg");
        TrtAdvisorContextService svc = service(List.of(), List.of(med));

        assertThat(svc.isOnTrt(USER)).isFalse();
    }

    // ---- (d) trend computed from two reports --------------------------------------------

    @Test
    @DisplayName("trend is RISING when the newest value exceeds the prior report")
    void trendRisingAcrossTwoReports() {
        BloodTestReport newer = report(RECENT, List.of(
            marker("Total Testosterone", 600.0, "ng/dL", 264.0, 916.0, null)));
        BloodTestReport older = report(OLDER, List.of(
            marker("Total Testosterone", 400.0, "ng/dL", 264.0, 916.0, null)));
        // Repository contract is newest-first.
        TrtAdvisorContextService svc = service(List.of(newer, older), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.markers())
            .filteredOn(m -> m.name().equals("totalTestosterone"))
            .singleElement()
            .satisfies(m -> assertThat(m.trend()).isEqualTo(Trend.RISING));
    }

    @Test
    @DisplayName("trend is UNKNOWN with a single report")
    void trendUnknownWithSingleReport() {
        BloodTestReport only = report(RECENT, List.of(
            marker("Total Testosterone", 600.0, "ng/dL", 264.0, 916.0, null)));
        TrtAdvisorContextService svc = service(List.of(only), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.markers())
            .singleElement()
            .satisfies(m -> assertThat(m.trend()).isEqualTo(Trend.UNKNOWN));
    }

    // ---- (e) no data: onTrt false, empty markers/flags, render "" -----------------------

    @Test
    @DisplayName("with no data: onTrt=false, empty markers/flags, renderForPrompt returns empty")
    void noDataYieldsEmptyContext() {
        TrtAdvisorContextService svc = service(List.of(), List.of());

        TrtContext ctx = svc.build(USER);

        assertThat(ctx.onTrt()).isFalse();
        assertThat(ctx.markers()).isEmpty();
        assertThat(ctx.dangerFlags()).isEmpty();
        assertThat(svc.renderForPrompt(USER)).isEmpty();
    }

    @Test
    @DisplayName("renderForPrompt is non-empty and cited when grounding data exists")
    void renderForPromptCitedWhenGrounded() {
        BloodTestReport report = report(RECENT, List.of(
            marker("Hematocrit", 55.0, "%", 38.0, 50.0, "H")));
        TrtAdvisorContextService svc = service(List.of(report), List.of());

        String rendered = svc.renderForPrompt(USER);

        assertThat(rendered)
            .isNotEmpty()
            .contains("ADR-0015")
            .contains("Endocrine Society")
            .containsIgnoringCase("cite a KB source")
            .containsIgnoringCase("DANGER");
    }

    @Test
    @DisplayName("markerHistory returns all matching points newest-first")
    void markerHistoryNewestFirst() {
        BloodTestReport newer = report(RECENT, List.of(
            marker("PSA", 1.2, "ng/mL", 0.0, 4.0, null)));
        BloodTestReport older = report(OLDER, List.of(
            marker("PSA", 0.9, "ng/mL", 0.0, 4.0, null)));
        TrtAdvisorContextService svc = service(List.of(newer, older), List.of());

        List<TrtMarkerHistoryPoint> history = svc.markerHistory(USER, "psa");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).date()).isEqualTo(RECENT);
        assertThat(history.get(0).value()).isEqualTo(1.2);
        assertThat(history.get(1).date()).isEqualTo(OLDER);
    }

    // ==== fixtures & fakes ===============================================================

    private TrtAdvisorContextService service(List<BloodTestReport> reports, List<Medication> meds) {
        return service(reports, meds, List.of());
    }

    private TrtAdvisorContextService service(
        List<BloodTestReport> reports, List<Medication> meds, List<Drug> drugs) {
        return new TrtAdvisorContextService(
            new FakeBloodTestReportRepository(reports),
            new FakeMedicationRepository(meds),
            new TrtGuidelineKnowledgeBase(),
            new FakeDrugRepository(drugs));
    }

    private static BloodTestReport report(LocalDate sampleDate, List<ExtractedMarker> markers) {
        return new BloodTestReport(
            USER, "report-" + sampleDate, sampleDate, "Quest", "path", "hash",
            markers, Instant.now(), Instant.now());
    }

    private static ExtractedMarker marker(
        String name, Double value, String unit, Double low, Double high, String flag) {
        return new ExtractedMarker(name, value, unit, low, high, flag);
    }

    private static Medication trtMedViaDrug(String drugId) {
        return Medication.create(
            USER, "med-1", drugId, 200, "mg",
            FrequencyConfig.prn(), List.of(),
            LocalDate.of(2026, 1, 1), List.of("TESTOSTERONE"));
    }

    private static Medication customNameMed(String customName) {
        // Build directly so we can set customName (create() leaves it null).
        return new Medication(
            USER, "med-2", "drug-x", customName, MedicationStatus.ACTIVE,
            200, "mg", FrequencyConfig.prn(), List.of(), null, null, null,
            LocalDate.of(2026, 1, 1), null, null, null, List.of(),
            List.of(DosagePeriod.initial(200, "mg", LocalDate.of(2026, 1, 1))),
            Instant.now(), Instant.now());
    }

    private static Drug drug(String drugId, String name, List<String> aliases) {
        return new Drug(
            drugId, name, aliases, null, null, "mg", List.of(),
            null, List.of(), null, List.of("TESTOSTERONE"), null,
            Instant.now(), Instant.now(), null);
    }

    private static final class FakeBloodTestReportRepository implements BloodTestReportRepository {
        private final List<BloodTestReport> reports;
        FakeBloodTestReportRepository(List<BloodTestReport> reports) { this.reports = reports; }
        @Override public List<BloodTestReport> findByUser(String userId) { return reports; }
        @Override public void save(BloodTestReport report) { throw new UnsupportedOperationException(); }
        @Override public Optional<BloodTestReport> findById(String userId, String reportId) { throw new UnsupportedOperationException(); }
        @Override public Optional<BloodTestReport> findByContentHash(String userId, String contentHash) { throw new UnsupportedOperationException(); }
        @Override public void delete(String userId, String reportId) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeMedicationRepository implements MedicationRepository {
        private final List<Medication> meds;
        FakeMedicationRepository(List<Medication> meds) { this.meds = meds; }
        @Override public List<Medication> findByUserAndStatus(String userId, MedicationStatus status) {
            List<Medication> out = new ArrayList<>();
            for (Medication m : meds) {
                if (m.status() == status) out.add(m);
            }
            return out;
        }
        @Override public Optional<Medication> findById(String userId, String medicationId) { throw new UnsupportedOperationException(); }
        @Override public List<Medication> findByUser(String userId) { throw new UnsupportedOperationException(); }
        @Override public List<Medication> findByProtocol(String userId, String protocolId) { throw new UnsupportedOperationException(); }
        @Override public void save(Medication medication) { throw new UnsupportedOperationException(); }
        @Override public void delete(String userId, String medicationId) { throw new UnsupportedOperationException(); }
        @Override public List<Medication> findAllReferencingDrug(String drugId) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeDrugRepository implements com.gte619n.healthfitness.core.medication.DrugRepository {
        private final List<Drug> drugs;
        FakeDrugRepository(List<Drug> drugs) { this.drugs = drugs; }
        @Override public Optional<Drug> findById(String drugId) {
            return drugs.stream().filter(d -> d.drugId().equals(drugId)).findFirst();
        }
        @Override public List<Drug> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<Drug> search(String query) { throw new UnsupportedOperationException(); }
        @Override public Optional<Drug> findByNameIgnoreCase(String name) { throw new UnsupportedOperationException(); }
        @Override public void save(Drug drug) { throw new UnsupportedOperationException(); }
        @Override public void delete(String drugId) { throw new UnsupportedOperationException(); }
    }
}
