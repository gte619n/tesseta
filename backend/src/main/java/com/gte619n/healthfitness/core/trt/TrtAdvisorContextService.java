package com.gte619n.healthfitness.core.trt;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.MedicationStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Assembles grounded TRT decision-support context for the program designer
 * (ADR-0015): the user's TRT status, the latest monitoring-panel lab values
 * (with trend and status vs. range), mandatory danger flags, and the cited
 * guideline KB rendered for the LLM prompt.
 *
 * <p>This service only reads the user's own real data. It never invents
 * clinical specifics — the model is expected to cite the KB and these grounded
 * values rather than free-associate from parametric memory.
 */
@Service
public class TrtAdvisorContextService {

    /** Substrings (lower-cased) that mark an active medication as testosterone therapy. */
    private static final List<String> TRT_NAME_TOKENS = List.of(
        "testosterone", "trt", "cypionate", "enanthate", "test cyp", "test e",
        "nebido", "sustanon", "androgel", "testogel", "undecanoate", "depo-testosterone",
        "aveed", "xyosted");

    /** Tokens that mark a correlated marker as a testosterone marker. */
    private static final List<String> TRT_MARKER_TOKENS = List.of(
        "testosterone", "free_testosterone", "free testosterone", "total_testosterone", "total t");

    private final BloodTestReportRepository bloodTestReportRepository;
    private final MedicationRepository medicationRepository;
    private final TrtGuidelineKnowledgeBase kb;
    private final DrugRepository drugRepository; // nullable — fall back to customName/notes if absent

    public TrtAdvisorContextService(
        BloodTestReportRepository bloodTestReportRepository,
        MedicationRepository medicationRepository,
        TrtGuidelineKnowledgeBase kb,
        DrugRepository drugRepository) {
        this.bloodTestReportRepository = bloodTestReportRepository;
        this.medicationRepository = medicationRepository;
        this.kb = kb;
        this.drugRepository = drugRepository;
    }

    // ---- TRT-panel marker definitions (canonical key, label, synonyms) ----------------

    private enum PanelMarker {
        TOTAL_TESTOSTERONE("totalTestosterone", "Total Testosterone",
            List.of("total testosterone", "testosterone, total", "testosterone total", "total t")),
        // NOTE: bare "testosterone" matches both total & free; total handles the bare token below.
        FREE_TESTOSTERONE("freeTestosterone", "Free Testosterone",
            List.of("free testosterone", "testosterone, free", "free t")),
        ESTRADIOL("estradiol", "Estradiol",
            List.of("estradiol", "e2")),
        HEMATOCRIT("hematocrit", "Hematocrit",
            List.of("hematocrit", "hct")),
        HEMOGLOBIN("hemoglobin", "Hemoglobin",
            List.of("hemoglobin", "hgb", "hb")),
        LDL("ldl", "LDL Cholesterol",
            List.of("ldl-c", "ldl")),
        HDL("hdl", "HDL Cholesterol",
            List.of("hdl-c", "hdl")),
        TRIGLYCERIDES("triglycerides", "Triglycerides",
            List.of("triglycerides", "trig")),
        PSA("psa", "PSA",
            List.of("prostate specific antigen", "psa"));

        final String key;
        final String label;
        final List<String> synonyms; // lower-case

        PanelMarker(String key, String label, List<String> synonyms) {
            this.key = key;
            this.label = label;
            this.synonyms = synonyms;
        }

        boolean matches(String rawName) {
            if (rawName == null) return false;
            String n = rawName.toLowerCase(Locale.ROOT).trim();
            for (String syn : synonyms) {
                if (n.contains(syn)) {
                    // Disambiguate the bare "testosterone" token: a name that mentions
                    // "free" must not be claimed by TOTAL_TESTOSTERONE, and vice-versa.
                    if (this == TOTAL_TESTOSTERONE && n.contains("free")) return false;
                    if (this == FREE_TESTOSTERONE && !n.contains("free")) return false;
                    return true;
                }
            }
            // Bare "testosterone" with no total/free qualifier -> treat as total testosterone.
            if (this == TOTAL_TESTOSTERONE && n.contains("testosterone") && !n.contains("free")) {
                return true;
            }
            return false;
        }
    }

    // ---- isOnTrt -----------------------------------------------------------------------

    public boolean isOnTrt(String userId) {
        List<Medication> active = medicationRepository.findByUserAndStatus(userId, MedicationStatus.ACTIVE);
        if (active == null) return false;
        for (Medication med : active) {
            if (looksLikeTrt(med)) return true;
        }
        return false;
    }

    private boolean looksLikeTrt(Medication med) {
        if (med == null) return false;

        StringBuilder haystack = new StringBuilder();
        if (med.customName() != null) haystack.append(med.customName()).append(' ');
        if (med.notes() != null) haystack.append(med.notes()).append(' ');

        // Resolve the catalog drug name (and aliases) when a drug repository is wired in.
        if (drugRepository != null && med.drugId() != null) {
            Optional<Drug> drug = drugRepository.findById(med.drugId());
            if (drug.isPresent()) {
                Drug d = drug.get();
                if (d.name() != null) haystack.append(d.name()).append(' ');
                if (d.aliases() != null) {
                    for (String a : d.aliases()) {
                        if (a != null) haystack.append(a).append(' ');
                    }
                }
            }
        }

        String text = haystack.toString().toLowerCase(Locale.ROOT);
        for (String token : TRT_NAME_TOKENS) {
            if (text.contains(token)) return true;
        }

        if (med.correlatedMarkers() != null) {
            for (String marker : med.correlatedMarkers()) {
                if (marker == null) continue;
                String m = marker.toLowerCase(Locale.ROOT);
                for (String token : TRT_MARKER_TOKENS) {
                    if (m.contains(token)) return true;
                }
            }
        }
        return false;
    }

    // ---- build -------------------------------------------------------------------------

    public TrtContext build(String userId) {
        boolean onTrt = isOnTrt(userId);
        List<BloodTestReport> reports = safeReports(userId); // newest-first

        List<TrtMarker> markers = new ArrayList<>();
        List<DangerFlag> flags = new ArrayList<>();

        for (PanelMarker pm : PanelMarker.values()) {
            // Latest + previous matching reading (reports are newest-first).
            ExtractedMarker latest = null;
            ExtractedMarker previous = null;
            LocalDate latestDate = null;
            for (BloodTestReport report : reports) {
                ExtractedMarker em = firstMatch(report, pm);
                if (em == null) continue;
                if (latest == null) {
                    latest = em;
                    latestDate = report.sampleDate();
                } else {
                    previous = em;
                    break;
                }
            }
            if (latest == null) continue;

            Trend trend = computeTrend(previous, latest);
            MarkerStatus status = computeStatus(pm, latest);
            markers.add(new TrtMarker(
                pm.key, pm.label, latest.value(), latest.unit(),
                latest.refRangeLow(), latest.refRangeHigh(), latestDate, trend, status));
        }

        // Danger flags are computed from the same matched readings, independent of anything else.
        flags.addAll(computeDangerFlags(reports));

        return new TrtContext(onTrt, List.copyOf(markers), List.copyOf(flags));
    }

    // ---- markerHistory -----------------------------------------------------------------

    public List<TrtMarkerHistoryPoint> markerHistory(String userId, String markerName) {
        PanelMarker pm = resolvePanelMarker(markerName);
        List<TrtMarkerHistoryPoint> out = new ArrayList<>();
        if (pm == null) return out;
        for (BloodTestReport report : safeReports(userId)) { // newest-first
            ExtractedMarker em = firstMatch(report, pm);
            if (em == null) continue;
            out.add(new TrtMarkerHistoryPoint(
                report.sampleDate(), em.value(), em.unit(),
                em.refRangeLow(), em.refRangeHigh()));
        }
        return out;
    }

    // ---- renderForPrompt ---------------------------------------------------------------

    public String renderForPrompt(String userId) {
        TrtContext ctx = build(userId);

        // Nothing to ground on: no labs AND not on TRT.
        if (!ctx.onTrt() && ctx.markers().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("GROUNDED TRT DECISION-SUPPORT (ADR-0015) — ENABLED.\n");
        sb.append("This is decision-support for the app owner's own prescribed therapy, ");
        sb.append("grounded in their real labs and the cited guideline KB below.\n");
        sb.append("TRT status: ").append(ctx.onTrt()
            ? "user appears to be ON testosterone therapy (active TRT medication detected)."
            : "no active TRT medication detected.").append("\n");

        sb.append("\nCURRENT MONITORING-PANEL VALUES (latest, vs. reference range, with trend):\n");
        if (ctx.markers().isEmpty()) {
            sb.append("- (no bloodwork on file for the TRT monitoring panel)\n");
        } else {
            for (TrtMarker m : ctx.markers()) {
                sb.append("- ").append(m.label()).append(": ");
                sb.append(formatValue(m.value())).append(m.unit() == null ? "" : (" " + m.unit()));
                sb.append(" [range ").append(formatValue(m.refLow())).append("-")
                  .append(formatValue(m.refHigh())).append("]");
                sb.append(", status=").append(m.status());
                sb.append(", trend=").append(m.trend());
                if (m.sampleDate() != null) sb.append(", sampled ").append(m.sampleDate());
                sb.append("\n");
            }
        }

        sb.append("\nDANGER FLAGS:\n");
        if (ctx.dangerFlags().isEmpty()) {
            sb.append("- none fired this turn.\n");
        } else {
            for (DangerFlag f : ctx.dangerFlags()) {
                sb.append("- [").append(f.severity()).append("] ")
                  .append(f.marker()).append(": ").append(f.message()).append("\n");
            }
        }

        sb.append("\n").append(kb.render());

        sb.append("\nINSTRUCTIONS: Every clinical claim MUST cite a KB source ");
        sb.append("(use the entry's Source). ALWAYS surface any danger flag above to the user, ");
        sb.append("regardless of what they asked. Ground numbers in the user's own values shown ");
        sb.append("above; do not assert un-grounded specifics from memory.\n");

        return sb.toString();
    }

    // ---- danger-flag rules (S6e / ADR-0015) --------------------------------------------

    private List<DangerFlag> computeDangerFlags(List<BloodTestReport> reports) {
        List<DangerFlag> flags = new ArrayList<>();

        // Hematocrit: > 54% (or > 0.54 fraction) = DANGER; 52..54 = WARNING.
        ExtractedMarker hct = latestMatch(reports, PanelMarker.HEMATOCRIT);
        if (hct != null && hct.value() != null) {
            double pct = toHematocritPercent(hct.value());
            if (pct > 54.0) {
                flags.add(new DangerFlag(
                    PanelMarker.HEMATOCRIT.key,
                    Severity.DANGER,
                    "Hematocrit " + formatValue(pct) + "% exceeds the 54% threshold — "
                        + "stop/reduce dose and contact your clinician promptly (polycythemia risk)."));
            } else if (pct >= 52.0) {
                flags.add(new DangerFlag(
                    PanelMarker.HEMATOCRIT.key,
                    Severity.WARNING,
                    "Hematocrit " + formatValue(pct) + "% is approaching the 54% watch line — "
                        + "monitor closely and review your dose/interval with your clinician."));
            }
        }

        // PSA: confirmed > 4.0 => DANGER; rise > 1.4 ng/mL within ~12 months => WARNING.
        List<ExtractedMarker> psaSeries = new ArrayList<>();
        List<LocalDate> psaDates = new ArrayList<>();
        for (BloodTestReport report : reports) { // newest-first
            ExtractedMarker em = firstMatch(report, PanelMarker.PSA);
            if (em != null && em.value() != null) {
                psaSeries.add(em);
                psaDates.add(report.sampleDate());
            }
        }
        if (!psaSeries.isEmpty()) {
            double latestPsa = psaSeries.get(0).value();
            if (latestPsa > 4.0) {
                flags.add(new DangerFlag(
                    PanelMarker.PSA.key,
                    Severity.DANGER,
                    "PSA " + formatValue(latestPsa) + " ng/mL exceeds 4.0 ng/mL — "
                        + "this warrants prompt urology referral; confirm with a repeat test."));
            }
            if (psaSeries.size() >= 2) {
                double prevPsa = psaSeries.get(1).value();
                double delta = latestPsa - prevPsa;
                boolean within12mo = true;
                LocalDate d0 = psaDates.get(0);
                LocalDate d1 = psaDates.get(1);
                if (d0 != null && d1 != null) {
                    within12mo = Math.abs(ChronoUnit.MONTHS.between(d1, d0)) <= 12;
                }
                if (delta > 1.4 && within12mo) {
                    flags.add(new DangerFlag(
                        PanelMarker.PSA.key,
                        Severity.WARNING,
                        "PSA rose " + formatValue(delta) + " ng/mL (from " + formatValue(prevPsa)
                            + " to " + formatValue(latestPsa) + ") within ~12 months — "
                            + "a rise > 1.4 ng/mL warrants urology referral."));
                }
            }
        }

        // Estradiol markedly above range => WARNING (>= 1.5x the reference high).
        ExtractedMarker e2 = latestMatch(reports, PanelMarker.ESTRADIOL);
        if (e2 != null && e2.value() != null && e2.refRangeHigh() != null
            && e2.value() > e2.refRangeHigh() * 1.5) {
            flags.add(new DangerFlag(
                PanelMarker.ESTRADIOL.key,
                Severity.WARNING,
                "Estradiol " + formatValue(e2.value())
                    + " is markedly above the reference high (" + formatValue(e2.refRangeHigh())
                    + ") — review dose/interval and symptoms with your clinician."));
        }

        // Total testosterone above its reference high => WARNING (supratherapeutic).
        ExtractedMarker totalT = latestMatch(reports, PanelMarker.TOTAL_TESTOSTERONE);
        if (totalT != null && totalT.value() != null && totalT.refRangeHigh() != null
            && totalT.value() > totalT.refRangeHigh()) {
            flags.add(new DangerFlag(
                PanelMarker.TOTAL_TESTOSTERONE.key,
                Severity.WARNING,
                "Total testosterone " + formatValue(totalT.value())
                    + " is above the reference high (" + formatValue(totalT.refRangeHigh())
                    + ") — supratherapeutic; consider reducing dose or extending the interval."));
        }

        return flags;
    }

    // ---- helpers -----------------------------------------------------------------------

    private List<BloodTestReport> safeReports(String userId) {
        List<BloodTestReport> reports = bloodTestReportRepository.findByUser(userId);
        return reports == null ? List.of() : reports;
    }

    private ExtractedMarker firstMatch(BloodTestReport report, PanelMarker pm) {
        if (report == null || report.markers() == null) return null;
        for (ExtractedMarker em : report.markers()) {
            if (em != null && pm.matches(em.name())) return em;
        }
        return null;
    }

    /** Latest (newest report) matching reading for a panel marker, or null. */
    private ExtractedMarker latestMatch(List<BloodTestReport> reports, PanelMarker pm) {
        for (BloodTestReport report : reports) { // newest-first
            ExtractedMarker em = firstMatch(report, pm);
            if (em != null) return em;
        }
        return null;
    }

    private PanelMarker resolvePanelMarker(String markerName) {
        if (markerName == null) return null;
        // Direct key match first (canonical client key), then synonym matching.
        for (PanelMarker pm : PanelMarker.values()) {
            if (pm.key.equalsIgnoreCase(markerName.trim())) return pm;
        }
        for (PanelMarker pm : PanelMarker.values()) {
            if (pm.matches(markerName)) return pm;
        }
        return null;
    }

    private Trend computeTrend(ExtractedMarker previous, ExtractedMarker latest) {
        if (latest == null || latest.value() == null || previous == null || previous.value() == null) {
            return Trend.UNKNOWN;
        }
        double prev = previous.value();
        double cur = latest.value();
        // Use a small relative epsilon so noise doesn't read as a trend.
        double epsilon = Math.max(1e-9, Math.abs(prev) * 0.02);
        if (cur - prev > epsilon) return Trend.RISING;
        if (prev - cur > epsilon) return Trend.FALLING;
        return Trend.STABLE;
    }

    private MarkerStatus computeStatus(PanelMarker pm, ExtractedMarker em) {
        if (em == null || em.value() == null) return MarkerStatus.UNKNOWN;

        // Hematocrit gets the explicit 52-54 WATCH band over and above the range check.
        if (pm == PanelMarker.HEMATOCRIT) {
            double pct = toHematocritPercent(em.value());
            if (pct > 54.0) return MarkerStatus.HIGH;
            if (pct >= 52.0) return MarkerStatus.WATCH;
            // else fall through to range-based status
        }

        Double low = em.refRangeLow();
        Double high = em.refRangeHigh();
        double v = em.value();
        if (low == null && high == null) return MarkerStatus.UNKNOWN;
        if (high != null && v > high) return MarkerStatus.HIGH;
        if (low != null && v < low) return MarkerStatus.LOW;
        return MarkerStatus.IN_RANGE;
    }

    /** Normalize hematocrit to a percent: values <= 1 are treated as a fraction (e.g. 0.55 -> 55). */
    private double toHematocritPercent(double value) {
        return value <= 1.0 ? value * 100.0 : value;
    }

    private String formatValue(Double v) {
        if (v == null) return "—";
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) (double) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}
