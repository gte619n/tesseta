import Link from "next/link";
import { revalidatePath } from "next/cache";
import { apiFetch, apiJson } from "@/lib/api";
import { AddReadingButton } from "@/components/bloodtest/AddReadingButton";
import { BloodTestUploadButton } from "@/components/bloodtest/BloodTestUploadButton";
import { ExpandableReport } from "@/components/bloodtest/ExpandableReport";
import { MarkerTooltip } from "@/components/bloodtest/MarkerTooltip";

type Marker =
  | "TESTOSTERONE"
  | "TOTAL_CHOLESTEROL"
  | "LDL"
  | "HDL"
  | "TRIGLYCERIDES"
  | "APO_B"
  | "HBA1C"
  | "FASTING_GLUCOSE"
  | "HS_CRP";

type Reference = {
  unit: string;
  orientation: "LOWER_IS_BETTER" | "HIGHER_IS_BETTER";
  goodThreshold: number;
  displayMin: number;
  displayMax: number;
};

type BloodReading = {
  readingId: string;
  marker: Marker;
  value: number;
  unit: string;
  sampleDate: string;
  labSource: string | null;
  notes: string | null;
  reference: Reference;
};

type ExtractedMarker = {
  name: string;
  value: number | null;
  unit: string | null;
  refRangeLow: number | null;
  refRangeHigh: number | null;
  flag: "H" | "L" | null;
};

type BloodTestReport = {
  reportId: string;
  sampleDate: string | null;
  labSource: string;
  markers: ExtractedMarker[];
};

// Unified marker data for display
type LatestMarker = {
  name: string;
  value: number | null;
  unit: string;
  sampleDate: string | null;
  refLow: number | null;
  refHigh: number | null;
  flag: "H" | "L" | null;
  source: "reading" | "report";
};

// Historical data point for trend graphs
type HistoryPoint = {
  date: string;
  value: number;
};

const MARKER_LABELS: Record<Marker, string> = {
  TESTOSTERONE: "Testosterone",
  TOTAL_CHOLESTEROL: "Total cholesterol",
  LDL: "LDL",
  HDL: "HDL",
  TRIGLYCERIDES: "Triglycerides",
  APO_B: "ApoB",
  HBA1C: "HbA1c",
  FASTING_GLUCOSE: "Fasting glucose",
  HS_CRP: "hs-CRP",
};

const MARKER_INFO: Record<string, { description: string; target: string }> = {
  TESTOSTERONE: {
    description: "Primary male sex hormone. Affects muscle mass, bone density, and energy levels.",
    target: "300–1000 ng/dL (men), 15–70 ng/dL (women)",
  },
  LDL: {
    description: "Low-density lipoprotein. Carries cholesterol to cells; high levels increase cardiovascular risk.",
    target: "< 100 mg/dL optimal, < 70 mg/dL for high-risk individuals",
  },
  APO_B: {
    description: "Apolipoprotein B. More accurate predictor of cardiovascular risk than LDL alone.",
    target: "< 90 mg/dL optimal, < 80 mg/dL for high-risk",
  },
  HDL: {
    description: "High-density lipoprotein. 'Good' cholesterol that removes LDL from arteries.",
    target: "> 40 mg/dL (men), > 50 mg/dL (women); > 60 mg/dL is protective",
  },
  TRIGLYCERIDES: {
    description: "Fat in your blood. High levels increase heart disease and pancreatitis risk.",
    target: "< 150 mg/dL normal, < 100 mg/dL optimal",
  },
  TOTAL_CHOLESTEROL: {
    description: "Sum of LDL, HDL, and 20% of triglycerides. Less informative than individual components.",
    target: "< 200 mg/dL desirable",
  },
  HBA1C: {
    description: "Glycated hemoglobin. 3-month average of blood sugar levels. Key diabetes marker.",
    target: "< 5.7% normal, 5.7–6.4% prediabetes, ≥ 6.5% diabetes",
  },
  FASTING_GLUCOSE: {
    description: "Blood sugar after 8+ hours fasting. Measures how well your body regulates glucose.",
    target: "< 100 mg/dL normal, 100–125 mg/dL prediabetes",
  },
  HS_CRP: {
    description: "High-sensitivity C-reactive protein. Marker of inflammation; predicts cardiovascular events.",
    target: "< 1 mg/L low risk, 1–3 mg/L average, > 3 mg/L high risk",
  },
};

const MARKER_ORDER: Marker[] = [
  "TESTOSTERONE",
  "LDL",
  "APO_B",
  "HDL",
  "TRIGLYCERIDES",
  "TOTAL_CHOLESTEROL",
  "HBA1C",
  "FASTING_GLUCOSE",
  "HS_CRP",
];

// Patterns to match marker names (checked in order, first match wins)
const MARKER_PATTERNS: { pattern: RegExp; marker: Marker }[] = [
  // Testosterone - match variations like "Testosterone, Total, LC/MS"
  { pattern: /\btestosterone\b/i, marker: "TESTOSTERONE" },

  // ApoB - check before general cholesterol patterns
  { pattern: /\bapob\b|\bapo[\s-]?b\b|\bapolipoprotein[\s-]?b\b/i, marker: "APO_B" },

  // LDL - check before total cholesterol
  { pattern: /\bldl\b/i, marker: "LDL" },

  // HDL - check before total cholesterol
  { pattern: /\bhdl\b/i, marker: "HDL" },

  // Triglycerides
  { pattern: /\btriglyceride/i, marker: "TRIGLYCERIDES" },
  { pattern: /\btrigs?\b/i, marker: "TRIGLYCERIDES" },

  // Total Cholesterol - after LDL/HDL to avoid false matches
  { pattern: /\btotal\s*cholesterol\b|\bcholesterol[\s,]*total\b/i, marker: "TOTAL_CHOLESTEROL" },
  { pattern: /^cholesterol$/i, marker: "TOTAL_CHOLESTEROL" },

  // HbA1c - various formats
  { pattern: /\bhba1c\b|\bhgba1c\b|\bhemoglobin\s*a1c\b|\bglycated\s*hemoglobin\b/i, marker: "HBA1C" },
  { pattern: /^a1c$/i, marker: "HBA1C" },

  // Fasting Glucose
  { pattern: /\bfasting\s*glucose\b|\bglucose[\s,]*fasting\b/i, marker: "FASTING_GLUCOSE" },
  { pattern: /\bfasting\s*blood\s*sugar\b|\bfbs\b/i, marker: "FASTING_GLUCOSE" },
  { pattern: /^glucose$/i, marker: "FASTING_GLUCOSE" },

  // hs-CRP
  { pattern: /\bhs[\s-]?crp\b|\bhigh[\s-]?sensitivity[\s-]?c[\s-]?reactive/i, marker: "HS_CRP" },
  { pattern: /\bc[\s-]?reactive[\s-]?protein\b|\bcrp\b/i, marker: "HS_CRP" },
];

function normalizeMarkerName(name: string): Marker | null {
  const trimmed = name.trim();
  for (const { pattern, marker } of MARKER_PATTERNS) {
    if (pattern.test(trimmed)) {
      return marker;
    }
  }
  return null;
}

export const dynamic = "force-dynamic";

export default async function BloodPage() {
  const [readings, reports] = await Promise.all([
    apiJson<BloodReading[]>("/api/me/blood"),
    apiJson<BloodTestReport[]>("/api/me/blood/reports"),
  ]);

  async function addReading(formData: FormData) {
    "use server";
    const marker = formData.get("marker") as Marker | null;
    const valueRaw = formData.get("value");
    const sampleDate = formData.get("sampleDate");
    const labSource = formData.get("labSource");
    const notes = formData.get("notes");

    if (!marker || !valueRaw || !sampleDate) {
      throw new Error("Missing required fields");
    }
    const value = Number(valueRaw);
    if (!Number.isFinite(value)) {
      throw new Error("Invalid value");
    }

    const res = await apiFetch("/api/me/blood", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        marker,
        value,
        sampleDate: String(sampleDate),
        labSource: labSource ? String(labSource) : null,
        notes: notes ? String(notes) : null,
      }),
    });
    if (!res.ok) {
      throw new Error(`Add failed: ${res.status}`);
    }
    revalidatePath("/me/blood");
  }

  async function deleteReport(reportId: string) {
    "use server";
    const res = await apiFetch(`/api/me/blood/reports/${reportId}`, {
      method: "DELETE",
    });
    if (!res.ok) {
      throw new Error(`Delete failed: ${res.status}`);
    }
    revalidatePath("/me/blood");
  }

  // Build latest values from both readings and reports
  const latestByMarker = buildLatestMarkers(readings, reports);
  const historyByMarker = buildMarkerHistory(readings, reports);
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const hasData = readings.length > 0 || reports.length > 0;

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        <header className="flex items-start justify-between">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Blood markers
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Latest readings across your lipid, glycemic, and inflammation panels.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <BloodTestUploadButton />
            <AddReadingButton
              addReading={addReading}
              markers={MARKER_ORDER}
              markerLabels={MARKER_LABELS}
            />
          </div>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">Latest</h2>
          </div>
          <div className="grid grid-cols-3 gap-px bg-border-subtle p-px">
            {MARKER_ORDER.map((key) => {
              const marker = latestByMarker[key];
              const history = historyByMarker[key] ?? [];
              const info = MARKER_INFO[key];
              const label = MARKER_LABELS[key];

              return (
                <div key={key} className="bg-surface p-4">
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <MarkerTooltip
                        content={
                          <div>
                            <div className="text-[13px] font-medium text-primary">
                              {label}
                            </div>
                            <p className="mt-1 text-[12px] leading-[1.5] text-secondary">
                              {info?.description}
                            </p>
                            <div className="mt-2 rounded bg-canvas px-2 py-1.5 font-mono text-[10px] text-tertiary">
                              Target: {info?.target}
                            </div>
                          </div>
                        }
                      >
                        <span className="text-[12px] text-secondary underline decoration-dotted underline-offset-2">
                          {label}
                        </span>
                      </MarkerTooltip>

                      <div className="mt-2">
                        {marker ? (
                          <span
                            className={`font-mono text-[22px] font-medium tabular-nums ${
                              marker.flag === "H"
                                ? "text-alert"
                                : marker.flag === "L"
                                  ? "text-warn"
                                  : "text-primary"
                            }`}
                          >
                            {marker.value?.toFixed(1) ?? "—"}
                            <span className="ml-1 text-[12px] font-normal text-tertiary">
                              {marker.unit}
                            </span>
                          </span>
                        ) : (
                          <span className="font-mono text-[22px] text-tertiary">
                            —
                          </span>
                        )}
                      </div>
                    </div>

                    {history.length > 1 && (
                      <div className="ml-2 flex-shrink-0">
                        <Sparkline
                          data={history}
                          refLow={marker?.refLow ?? null}
                          refHigh={marker?.refHigh ?? null}
                        />
                      </div>
                    )}
                  </div>

                  <div className="mt-2">
                    {marker && (marker.refLow !== null || marker.refHigh !== null) ? (
                      <RangeBar
                        value={marker.value}
                        low={marker.refLow}
                        high={marker.refHigh}
                      />
                    ) : (
                      <span className="font-mono text-[10px] text-tertiary">
                        No range data
                      </span>
                    )}
                  </div>

                  <div className="mt-1.5 font-mono text-[10px] text-tertiary">
                    {marker?.sampleDate ?? "No data"}
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Uploaded reports
            </h2>
          </div>
          {reports.length === 0 ? (
            <div className="px-5 py-8 text-center text-[13px] text-secondary">
              No results yet. Drop a lab result to enter your data.
            </div>
          ) : (
            <div className="divide-y divide-border-subtle">
              {reports.map((report) => (
                <ExpandableReport
                  key={report.reportId}
                  reportId={report.reportId}
                  sampleDate={report.sampleDate}
                  labSource={report.labSource}
                  markers={report.markers}
                  deleteReport={deleteReport}
                />
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  );
}

function RangeBar({
  value,
  low,
  high,
}: {
  value: number | null;
  low: number | null;
  high: number | null;
}) {
  if (value === null || (low === null && high === null)) {
    return <span className="font-mono text-[11px] text-tertiary">—</span>;
  }

  // Calculate position and status
  const refLow = low ?? 0;
  const refHigh = high ?? refLow * 2;
  const range = refHigh - refLow;
  const padding = range * 0.3;
  const displayMin = refLow - padding;
  const displayMax = refHigh + padding;
  const displayRange = displayMax - displayMin;

  const valuePct = Math.max(0, Math.min(100, ((value - displayMin) / displayRange) * 100));
  const lowPct = ((refLow - displayMin) / displayRange) * 100;
  const highPct = ((refHigh - displayMin) / displayRange) * 100;
  const rangePct = highPct - lowPct;

  const inRange = value >= refLow && value <= refHigh;
  const status = inRange ? "good" : value < refLow ? "low" : "high";

  return (
    <div className="flex items-center gap-2">
      <div className="relative h-1.5 w-24 rounded-full bg-canvas-sunken">
        {/* Good range highlight */}
        <div
          className="absolute h-full rounded-full bg-good/30"
          style={{ left: `${lowPct}%`, width: `${rangePct}%` }}
        />
        {/* Value indicator */}
        <div
          className={`absolute top-1/2 h-2.5 w-1 -translate-y-1/2 rounded-sm ${
            status === "good"
              ? "bg-good"
              : status === "low"
                ? "bg-warn"
                : "bg-alert"
          }`}
          style={{ left: `${valuePct}%` }}
        />
      </div>
      <span className="font-mono text-[10px] text-tertiary">
        {low ?? "—"}–{high ?? "—"}
      </span>
    </div>
  );
}

function buildLatestMarkers(
  readings: BloodReading[],
  reports: BloodTestReport[],
): Partial<Record<Marker, LatestMarker>> {
  const latest: Partial<Record<Marker, LatestMarker>> = {};

  // Process manual readings first
  for (const r of readings) {
    const existing = latest[r.marker];
    if (!existing || (existing.sampleDate && r.sampleDate > existing.sampleDate)) {
      latest[r.marker] = {
        name: MARKER_LABELS[r.marker],
        value: r.value,
        unit: r.unit,
        sampleDate: r.sampleDate,
        refLow: r.reference.displayMin,
        refHigh: r.reference.goodThreshold,
        flag: null,
        source: "reading",
      };
    }
  }

  // Process extracted markers from reports
  for (const report of reports) {
    for (const m of report.markers) {
      const canonicalName = normalizeMarkerName(m.name);
      if (!canonicalName) continue;

      const key = canonicalName as Marker;
      const existing = latest[key];
      const reportDate = report.sampleDate;

      if (!existing || (existing.sampleDate && reportDate && reportDate > existing.sampleDate)) {
        latest[key] = {
          name: m.name,
          value: m.value,
          unit: m.unit ?? "",
          sampleDate: reportDate,
          refLow: m.refRangeLow,
          refHigh: m.refRangeHigh,
          flag: m.flag,
          source: "report",
        };
      }
    }
  }

  return latest;
}

function buildMarkerHistory(
  readings: BloodReading[],
  reports: BloodTestReport[],
): Partial<Record<Marker, HistoryPoint[]>> {
  const history: Partial<Record<Marker, HistoryPoint[]>> = {};
  const oneYearAgo = new Date();
  oneYearAgo.setFullYear(oneYearAgo.getFullYear() - 1);
  const cutoffDate = oneYearAgo.toISOString().split("T")[0] ?? "";

  // Collect from readings
  for (const r of readings) {
    if (r.sampleDate < cutoffDate) continue;
    if (!history[r.marker]) history[r.marker] = [];
    history[r.marker]!.push({ date: r.sampleDate, value: r.value });
  }

  // Collect from reports
  for (const report of reports) {
    if (!report.sampleDate || report.sampleDate < cutoffDate) continue;
    for (const m of report.markers) {
      if (m.value === null) continue;
      const canonicalName = normalizeMarkerName(m.name);
      if (!canonicalName) continue;
      const key = canonicalName as Marker;
      if (!history[key]) history[key] = [];
      history[key]!.push({ date: report.sampleDate, value: m.value });
    }
  }

  // Sort each marker's history by date and dedupe same-date entries
  for (const key of Object.keys(history) as Marker[]) {
    const points = history[key]!;
    points.sort((a, b) => a.date.localeCompare(b.date));
    // Dedupe: keep last value per date
    const deduped: HistoryPoint[] = [];
    for (const p of points) {
      if (deduped.length > 0 && deduped[deduped.length - 1]!.date === p.date) {
        deduped[deduped.length - 1] = p;
      } else {
        deduped.push(p);
      }
    }
    history[key] = deduped;
  }

  return history;
}

function Sparkline({
  data,
  refLow,
  refHigh,
}: {
  data: HistoryPoint[];
  refLow: number | null;
  refHigh: number | null;
}) {
  if (data.length < 2) return null;

  const width = 64;
  const height = 32;
  const padding = 2;

  const values = data.map((d) => d.value);
  const minVal = Math.min(...values);
  const maxVal = Math.max(...values);
  const range = maxVal - minVal || 1;

  // Scale values to SVG coordinates
  const points = data.map((d, i) => {
    const x = padding + (i / (data.length - 1)) * (width - 2 * padding);
    const y = height - padding - ((d.value - minVal) / range) * (height - 2 * padding);
    return { x, y, value: d.value };
  });

  const pathD = points.map((p, i) => `${i === 0 ? "M" : "L"} ${p.x} ${p.y}`).join(" ");

  // Determine trend color based on last value vs reference range
  const lastValue = values[values.length - 1]!;
  const inRange =
    refLow !== null && refHigh !== null
      ? lastValue >= refLow && lastValue <= refHigh
      : true;
  const strokeColor = inRange ? "var(--color-good)" : "var(--color-alert)";

  return (
    <svg width={width} height={height} className="overflow-visible">
      {/* Reference range band */}
      {refLow !== null && refHigh !== null && (
        <rect
          x={padding}
          y={height - padding - ((Math.min(refHigh, maxVal) - minVal) / range) * (height - 2 * padding)}
          width={width - 2 * padding}
          height={
            (((Math.min(refHigh, maxVal) - Math.max(refLow, minVal)) / range) *
              (height - 2 * padding)) || 1
          }
          fill="var(--color-good)"
          opacity={0.15}
          rx={1}
        />
      )}
      {/* Trend line */}
      <path d={pathD} fill="none" stroke={strokeColor} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" />
      {/* Latest point dot */}
      <circle
        cx={points[points.length - 1]!.x}
        cy={points[points.length - 1]!.y}
        r={2.5}
        fill={strokeColor}
      />
    </svg>
  );
}
