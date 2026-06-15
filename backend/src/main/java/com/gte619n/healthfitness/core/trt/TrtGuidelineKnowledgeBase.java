package com.gte619n.healthfitness.core.trt;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Curated, cited, version-controlled knowledge base for grounded TRT
 * decision-support (ADR-0015).
 *
 * <p>This is a <b>maintained, reviewed artifact</b>: it is checked into the repo
 * and reviewed like code, and every entry carries a real source citation. It is
 * deliberately distilled — conservatively and factually — from authoritative
 * clinical guidance:
 *
 * <ul>
 *   <li>Bhasin S, et al. "Testosterone Therapy in Men With Hypogonadism: An
 *       Endocrine Society Clinical Practice Guideline." J Clin Endocrinol Metab.
 *       2018;103(5):1715–1744.</li>
 *   <li>Mulhall JP, et al. "Evaluation and Management of Testosterone Deficiency:
 *       AUA Guideline (2018)." J Urol. 2018;200(2):423–432.</li>
 * </ul>
 *
 * <p>The model uses {@link #render()} as grounding context: it must cite a KB
 * source for every clinical claim and must always surface danger flags. This KB
 * is not a substitute for the prescriber; it grounds the designer's guidance in
 * named, verifiable sources so the user and their clinician can check it.
 *
 * <p>Citation key:
 * <ul>
 *   <li><b>ES-2018</b> — Endocrine Society 2018 guideline (Bhasin et al.).</li>
 *   <li><b>AUA-2018</b> — AUA Testosterone Deficiency Guideline 2018 (Mulhall et al.).</li>
 * </ul>
 */
@Component
public class TrtGuidelineKnowledgeBase {

    private static final String ES =
        "Endocrine Society 2018 (Bhasin et al., J Clin Endocrinol Metab 2018;103(5):1715-1744)";
    private static final String AUA =
        "AUA Testosterone Deficiency Guideline 2018 (Mulhall et al., J Urol 2018;200(2):423-432)";

    private static final List<TrtGuidelineEntry> ENTRIES = List.of(
        new TrtGuidelineEntry(
            "trt-diagnosis-confirmation",
            "Diagnosis confirmation",
            "Diagnose hypogonadism only with consistent symptoms/signs AND unequivocally "
                + "low morning total testosterone on at least two separate fasting measurements; "
                + "transient illness and assay variation can lower a single reading.",
            ES + "; " + AUA),
        new TrtGuidelineEntry(
            "trt-total-t-target-range",
            "Total testosterone target & reference range",
            "Typical adult male total testosterone reference range is roughly 264-916 ng/dL "
                + "(harmonized CDC range cited by ES 2018). The therapy aim is to restore total "
                + "testosterone into the mid-normal range; AUA suggests a treatment target near "
                + "the middle of the normal range (~450-600 ng/dL is a commonly targeted mid-normal "
                + "band). Always interpret against the reporting lab's own reference range.",
            ES + "; " + AUA),
        new TrtGuidelineEntry(
            "trt-trough-mid-normal",
            "Mid-normal trough targeting",
            "Dose to a mid-normal level; with injectable esters, measure at trough (just before the "
                + "next dose) so the lowest concentration still sits comfortably in range rather than "
                + "the post-injection peak. Avoid pushing levels above the normal range.",
            ES),
        new TrtGuidelineEntry(
            "trt-free-testosterone",
            "Free testosterone",
            "Measure free testosterone (by equilibrium dialysis or calculated from total T, SHBG and "
                + "albumin) when SHBG abnormalities are suspected, since total T can mislead. Interpret "
                + "free T against the assay's own reference range.",
            ES),
        new TrtGuidelineEntry(
            "trt-titration-logic",
            "Titration logic",
            "Recheck testosterone and hematocrit at roughly 3-6 months after starting or changing dose, "
                + "then periodically once stable. Adjust dose in small steps based on the trough level and "
                + "symptoms; if trough is below mid-normal, raise modestly; if at/above the upper limit or "
                + "hematocrit is climbing, reduce dose or extend the interval. Do not chase peaks.",
            ES + "; " + AUA),
        new TrtGuidelineEntry(
            "trt-injection-cadence",
            "Injection cadence & protocol basics",
            "Testosterone cypionate or enanthate is commonly dosed about 75-100 mg weekly or 150-200 mg "
                + "every two weeks. More frequent, smaller doses (e.g. weekly or twice-weekly) flatten the "
                + "peak-to-trough swing and tend to reduce erythrocytosis and estradiol spikes versus large "
                + "every-2-week doses. Testosterone undecanoate (e.g. Nebido/Aveed) is a long-acting depot "
                + "given at extended intervals (about every 10-14 weeks after loading).",
            ES + "; " + AUA),
        new TrtGuidelineEntry(
            "trt-estradiol",
            "Estradiol monitoring & management",
            "Some testosterone aromatizes to estradiol; estradiol is not routinely measured in all "
                + "guidelines, but markedly elevated estradiol with symptoms (e.g. gynecomastia, fluid "
                + "retention) may prompt lowering/spacing the testosterone dose first. Routine aromatase-"
                + "inhibitor use is not endorsed by ES 2018; over-suppressing estradiol harms bone and libido.",
            ES),
        new TrtGuidelineEntry(
            "trt-hematocrit-threshold",
            "Hematocrit / erythrocytosis threshold (HARD DANGER LINE)",
            "Hematocrit is the key safety lab on TRT. Check baseline, then ~3-6 months, then annually. "
                + "If hematocrit exceeds 54%, STOP therapy until it normalizes and reassess (reduce dose, "
                + "lengthen interval, or evaluate for sleep apnea / other causes) before resuming at a lower "
                + "dose — this is the hard danger threshold for polycythemia. Values in the ~52-54% band are "
                + "a watch zone warranting closer monitoring and dose review.",
            ES + "; " + AUA),
        new TrtGuidelineEntry(
            "trt-hemoglobin",
            "Hemoglobin",
            "Hemoglobin tracks with hematocrit and rises on testosterone; a rising hemoglobin alongside a "
                + "rising hematocrit reinforces erythrocytosis concern. Use the hematocrit > 54% rule as the "
                + "actionable threshold.",
            ES),
        new TrtGuidelineEntry(
            "trt-lipids",
            "Lipid monitoring",
            "Testosterone therapy can modestly lower HDL cholesterol; monitor a lipid panel "
                + "(LDL-C, HDL-C, triglycerides) at baseline and periodically, and manage cardiovascular "
                + "risk per standard lipid guidelines. Out-of-range lipids warrant clinical review rather "
                + "than reflexive testosterone dose changes.",
            ES),
        new TrtGuidelineEntry(
            "trt-psa-monitoring",
            "PSA / prostate monitoring",
            "Check PSA and do a prostate risk assessment at baseline before starting (in men for whom "
                + "prostate-cancer detection would change management), then recheck PSA at 3-12 months and "
                + "periodically thereafter. Refer to urology if PSA rises more than 1.4 ng/mL within any "
                + "12-month period, if a confirmed PSA exceeds 4.0 ng/mL, or if there is a new prostate "
                + "abnormality on exam.",
            ES + "; " + AUA),
        new TrtGuidelineEntry(
            "trt-fertility",
            "Fertility consideration",
            "Exogenous testosterone suppresses spermatogenesis and can impair fertility; in men wishing to "
                + "preserve fertility, discuss alternatives (e.g. hCG or other agents) with the prescriber "
                + "before/while on therapy.",
            AUA),
        new TrtGuidelineEntry(
            "trt-side-effect-surveillance",
            "Side-effect surveillance",
            "On each follow-up, review for erythrocytosis (hematocrit), prostate change (PSA/exam), "
                + "lipid shifts, fluid retention, acne/oily skin, sleep-apnea worsening, and injection-site "
                + "issues. Persistent symptoms or threshold breaches warrant prompt clinician contact.",
            ES + "; " + AUA));

    /** The full, cited guideline entry list. */
    public List<TrtGuidelineEntry> all() {
        return ENTRIES;
    }

    /**
     * Render the KB as a compact, cited text block for an LLM grounding prompt.
     * Every line carries its source so the model can cite it.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("TRT GUIDELINE KNOWLEDGE BASE (curated, cited — ADR-0015).\n");
        sb.append("Sources: ES-2018 = ").append(ES).append("; ");
        sb.append("AUA-2018 = ").append(AUA).append(".\n");
        sb.append("Cite the source after every clinical claim. Entries:\n");
        for (TrtGuidelineEntry e : ENTRIES) {
            sb.append("- [").append(e.id()).append("] ")
              .append(e.topic()).append(": ")
              .append(e.guidance())
              .append(" (Source: ").append(e.source()).append(")\n");
        }
        return sb.toString();
    }
}
