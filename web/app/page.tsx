import Link from "next/link";
import type { Session } from "next-auth";
import { revalidatePath } from "next/cache";
import { auth } from "@/auth";
import { BloodPanel, type BloodPanelData, type BloodPanelMarker } from "@/components/dashboard/BloodPanel";
import { RecentFeed } from "@/components/dashboard/RecentFeed";
import { Sidebar, type SidebarUser } from "@/components/dashboard/Sidebar";
import { StatCard } from "@/components/dashboard/StatCard";
import { TodaysDosesCard } from "@/components/dashboard/TodaysDosesCard";
import { WeightChart } from "@/components/dashboard/WeightChart";
import { isAdmin } from "@/lib/admin";
import { apiFetch, apiJson } from "@/lib/api";
import { recent, todayHeader, vitals } from "@/lib/fixtures/dashboard";
import type { TodaysDose, TimeWindow } from "@/lib/types/medication";

// IMPL-04 wires the Sidebar identity and the BodyCompositionCard to real
// data. The other cards (StatCard row, BloodPanel, TodayCard, RecentFeed)
// remain on fixtures pending their own data sources — they'll get wired
// up as separate work lands.

const KG_TO_LB = 2.20462;
const NINETY_DAYS_MS = 90 * 24 * 60 * 60 * 1000;

type Metric = "WEIGHT_KG" | "BODY_FAT_PERCENT" | "LEAN_MASS_KG" | "BMI";

type Reading = {
  recordId: string;
  metric: Metric;
  value: number;
  sampleTime: string;
  sourcePlatform: string | null;
  recordingMethod: string | null;
};

import type { Vital } from "@/lib/fixtures/dashboard";

type BodyCompositionView = {
  primary: { value: string; unit: string; delta: string | null };
  secondary: { value: string; unit: string; delta: string | null }[];
  series: number[];
  yMin: number;
  yMax: number;
  xLabels: { x: number; label: string }[];
  weightVital: Vital | null;
};

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  const session = await auth();
  const sidebarUser = toSidebarUser(session);
  const [admin, view, bloodPanel, todaysDoses] = await Promise.all([
    isAdmin(),
    loadBodyComposition(),
    loadBloodPanel(),
    loadTodaysDoses(),
  ]);

  async function logDose(medicationId: string, window: TimeWindow) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}/adherence`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ window }),
    });
    if (!res.ok) {
      throw new Error(`Failed to log dose: ${res.status}`);
    }
    revalidatePath("/");
  }

  return (
    <div className="flex min-h-screen items-start justify-center p-8">
      <div className="grid w-[1200px] max-w-full grid-cols-[220px_1fr] overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-canvas shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <Sidebar user={sidebarUser} isAdmin={admin} />
        <main className="overflow-hidden px-7 pb-7 pt-[22px]">
          <TopBar />

          <section className="mb-3 grid grid-cols-4 gap-2.5">
            {composeStatRow(view?.weightVital ?? null).map((v) => (
              <StatCard key={v.label} stat={v} />
            ))}
          </section>

          <BodyCompositionCard view={view} />

          <section className="mb-3 grid grid-cols-2 gap-2.5">
            <BloodPanel data={bloodPanel} compact />
            <TodaysDosesCard doses={todaysDoses} logDose={logDose} compact />
          </section>

          <RecentFeed entries={recent} variant="desktop" />
        </main>
      </div>
    </div>
  );
}

function toSidebarUser(session: Session | null): SidebarUser {
  const name = session?.user?.name ?? session?.user?.email ?? "—";
  const email = session?.user?.email ?? null;
  return { name, email, initials: initialsFor(name) };
}

function initialsFor(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "—";
  if (parts.length === 1) return (parts[0] ?? "").slice(0, 2).toUpperCase();
  const first = parts[0] ?? "";
  const last = parts[parts.length - 1] ?? "";
  return ((first[0] ?? "") + (last[0] ?? "")).toUpperCase();
}

async function loadBodyComposition(): Promise<BodyCompositionView | null> {
  let readings: Reading[];
  try {
    readings = await apiJson<Reading[]>("/api/me/body-composition");
  } catch {
    return null;
  }
  if (readings.length === 0) return null;

  const now = Date.now();
  const ninetyDaysAgo = now - NINETY_DAYS_MS;

  const weightsAll = readings
    .filter((r) => r.metric === "WEIGHT_KG")
    .sort((a, b) => a.sampleTime.localeCompare(b.sampleTime));
  if (weightsAll.length === 0) return null;

  const weights90 = weightsAll.filter(
    (r) => new Date(r.sampleTime).getTime() >= ninetyDaysAgo,
  );
  const window = weights90.length >= 2 ? weights90 : weightsAll;

  const series = window.map((r) => r.value * KG_TO_LB);
  const latestWeight = series[series.length - 1] ?? 0;
  const avg90 = series.reduce((a, b) => a + b, 0) / series.length;
  const delta = latestWeight - avg90;
  const deltaStr = formatDelta(delta, "lb", "vs 90d avg");

  const latestBodyFat = readings
    .filter((r) => r.metric === "BODY_FAT_PERCENT")
    .sort((a, b) => b.sampleTime.localeCompare(a.sampleTime))[0];

  // Lean mass isn't sourced from Google Health; derive it from the most
  // recent weight + body-fat pair when both exist within a few hours.
  const leanMassLb = computeLeanMass(weightsAll, latestBodyFat);

  const yPadding = Math.max(1, (Math.max(...series) - Math.min(...series)) * 0.15);
  const yMin = Math.floor(Math.min(...series) - yPadding);
  const yMax = Math.ceil(Math.max(...series) + yPadding);

  const xLabels = buildXLabels(window);

  // 7-day delta for the top-row StatCard. Compare against the value
  // closest to (now - 7d).
  const sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000;
  const reference = [...window]
    .reverse()
    .find((r) => new Date(r.sampleTime).getTime() <= sevenDaysAgo);
  const sevenDayDelta = reference
    ? latestWeight - reference.value * KG_TO_LB
    : null;
  const weightVital: Vital = {
    label: "Weight",
    icon: "scale",
    value: latestWeight.toFixed(1),
    unit: "lb",
    delta:
      sevenDayDelta !== null
        ? {
            direction: sevenDayDelta < 0 ? "down" : "up",
            value: Math.abs(sevenDayDelta).toFixed(1),
            window: "7d",
            // Weight loss is typically the goal in this app's context; if
            // the user wants weight gain (cut/bulk cycles), this can grow
            // into a per-user preference later.
            tone: sevenDayDelta <= 0 ? "good" : "alert",
          }
        : undefined,
    sparkline: weightSparkline(series),
  };

  return {
    primary: {
      value: latestWeight.toFixed(1),
      unit: "lb",
      delta: deltaStr,
    },
    secondary: [
      {
        value: latestBodyFat ? latestBodyFat.value.toFixed(1) : "—",
        unit: "% fat",
        delta: null,
      },
      {
        value: leanMassLb !== null ? leanMassLb.toFixed(1) : "—",
        unit: "lb lean",
        delta: null,
      },
    ],
    series,
    yMin,
    yMax,
    xLabels,
    weightVital,
  };
}

// Builds the 4-card stat row at the top of the dashboard. The Weight
// card is computed from real backend data; the other three remain on
// fixtures until we have data sources for them.
function composeStatRow(weightVital: Vital | null): Vital[] {
  const remaining = vitals.slice(1); // HRV / Resting HR / Readiness
  const first = weightVital ?? vitals[0];
  return first ? [first, ...remaining] : remaining;
}

// Render the weight series as a 48×20 polyline (the dimensions of
// StatCard's sparkline). Picks 9 evenly-spaced points so the visual
// density matches the other StatCards.
function weightSparkline(series: number[]): string {
  if (series.length === 0) return "";
  const N = 9;
  const idxs = Array.from({ length: N }, (_, i) =>
    Math.round((i * (series.length - 1)) / (N - 1)),
  );
  const ys = idxs.map((i) => series[i] ?? 0);
  const min = Math.min(...ys);
  const max = Math.max(...ys);
  const range = max - min || 1;
  return ys
    .map((y, i) => {
      const x = (i * 48) / (N - 1);
      // Higher weight → lower y (top of viewBox). Pad 2px top/bottom.
      const yPx = 2 + ((max - y) / range) * 16;
      return `${x.toFixed(0)},${yPx.toFixed(0)}`;
    })
    .join(" ");
}

function formatDelta(delta: number, unit: string, suffix: string): string {
  if (Math.abs(delta) < 0.05) return `unchanged ${suffix}`;
  const arrow = delta < 0 ? "↓" : "↑";
  return `${arrow} ${Math.abs(delta).toFixed(1)} ${unit} ${suffix}`;
}

function computeLeanMass(
  weights: Reading[],
  bodyFat: Reading | undefined,
): number | null {
  if (!bodyFat) return null;
  const bfTime = new Date(bodyFat.sampleTime).getTime();
  const sixHours = 6 * 60 * 60 * 1000;
  // Find the weight reading closest in time to the body-fat reading,
  // within 6 hours, so we don't mix readings from different weigh-ins.
  let best: { reading: Reading; diff: number } | null = null;
  for (const w of weights) {
    const diff = Math.abs(new Date(w.sampleTime).getTime() - bfTime);
    if (diff <= sixHours && (best === null || diff < best.diff)) {
      best = { reading: w, diff };
    }
  }
  if (!best) return null;
  const weightLb = best.reading.value * KG_TO_LB;
  return weightLb * (1 - bodyFat.value / 100);
}

function buildXLabels(
  window: Reading[],
): { x: number; label: string }[] {
  if (window.length < 2) return [];
  const first = window[0];
  const last = window[window.length - 1];
  if (!first || !last) return [];
  // Four labels at fixed pixel positions matching the chart's 600px viewBox.
  // Pick four roughly-evenly spaced samples and format their sample dates.
  const ticks = [0, Math.floor(window.length / 3), Math.floor((2 * window.length) / 3), window.length - 1];
  const xs = [32, 180, 335, 500];
  return ticks.map((idx, i) => {
    const reading = window[Math.min(idx, window.length - 1)] ?? last;
    return { x: xs[i] ?? 0, label: shortDate(reading.sampleTime) };
  });
}

function shortDate(iso: string): string {
  const d = new Date(iso);
  const month = d.toLocaleString("en-US", { month: "short", timeZone: "UTC" }).toUpperCase();
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${month} ${day}`;
}

function TopBar() {
  return (
    <div className="mb-5 flex items-center justify-between">
      <div>
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Dashboard
        </h1>
        <div className="mt-[3px] font-mono text-[11px] tracking-[0.04em] text-tertiary tabular">
          {todayHeader.weekday} · {todayHeader.date} · {todayHeader.time} ·{" "}
          {todayHeader.tz}
        </div>
      </div>
      <div className="flex items-center gap-[7px]">
        <div className="flex cursor-pointer items-center gap-[7px] rounded-md border-[0.5px] border-border-default bg-surface px-3 py-[7px] font-mono text-[11px] tracking-[0.04em] text-secondary tabular">
          <i
            className="ti ti-calendar text-[13px] text-tertiary"
            aria-hidden
          />
          LAST 90 DAYS
          <i
            className="ti ti-chevron-down text-[12px] text-tertiary"
            aria-hidden
          />
        </div>
        <button
          type="button"
          aria-label="Search"
          className="flex h-[34px] w-[34px] cursor-pointer items-center justify-center rounded-md border-[0.5px] border-border-default bg-surface text-secondary"
        >
          <i className="ti ti-search text-[14px]" aria-hidden />
        </button>
        <button
          type="button"
          aria-label="Notifications"
          className="relative flex h-[34px] w-[34px] cursor-pointer items-center justify-center rounded-md border-[0.5px] border-border-default bg-surface text-secondary"
        >
          <i className="ti ti-bell text-[14px]" aria-hidden />
          <span
            aria-hidden
            className="absolute right-2 top-[7px] h-1.5 w-1.5 rounded-full border-[1.5px] border-surface bg-accent"
          />
        </button>
      </div>
    </div>
  );
}

function BodyCompositionCard({ view }: { view: BodyCompositionView | null }) {
  if (!view) {
    return (
      <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
        <BodyCompositionTitle />
        <p className="mt-3 text-[13px] leading-[1.55] text-secondary">
          No body-comp data yet.{" "}
          <Link
            className="font-medium text-accent-dim underline-offset-2 hover:underline"
            href="/me/body-composition"
          >
            Connect Google Health
          </Link>{" "}
          to start syncing weight and body fat from your scale.
        </p>
      </div>
    );
  }

  return (
    <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
      <div className="mb-3.5 flex items-start justify-between">
        <div>
          <BodyCompositionTitle />
          <div className="mt-3 flex items-baseline gap-[18px]">
            <div>
              <div className="font-mono text-[36px] font-medium leading-none tracking-[-0.03em] text-primary tabular">
                {view.primary.value}
                <span className="ml-1 text-[13px] font-normal text-tertiary">
                  {view.primary.unit}
                </span>
              </div>
              {view.primary.delta ? (
                <div className="caps-mono mt-[5px] text-[10px] text-good">
                  {view.primary.delta}
                </div>
              ) : null}
            </div>
            <div className="h-[42px] w-px bg-border-default" aria-hidden />
            {view.secondary.map((s, i) => (
              <div key={`${s.unit}-${i}`}>
                <div className="font-mono text-[18px] font-medium leading-none tracking-[-0.01em] text-primary tabular">
                  {s.value}
                  <span className="ml-[3px] text-[10px] font-normal text-tertiary">
                    {s.unit}
                  </span>
                </div>
                {s.delta ? (
                  <div className="caps-mono mt-[5px] text-[10px] text-good">
                    {s.delta}
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      </div>
      <WeightChart
        variant="desktop"
        series={view.series}
        yMin={view.yMin}
        yMax={view.yMax}
        xLabels={view.xLabels}
      />
      <div className="mt-2.5 flex gap-3.5 border-t-[0.5px] border-border-subtle pt-2.5">
        <div className="caps-mono flex items-center gap-1.5 text-[10px] tracking-[0.04em] text-secondary">
          <span
            aria-hidden
            className="inline-block h-[2px] w-2.5 rounded-[1px] bg-accent"
          />
          DAILY
        </div>
        <div className="caps-mono flex items-center gap-1.5 text-[10px] tracking-[0.04em] text-secondary">
          <span
            aria-hidden
            className="inline-block h-0 w-2.5 border-t border-dashed border-primary opacity-40"
          />
          7-DAY AVG
        </div>
      </div>
    </div>
  );
}

type BloodReadingApi = {
  readingId: string;
  marker: string;
  value: number;
  unit: string;
  sampleDate: string;
  reference: {
    unit: string;
    orientation: "LOWER_IS_BETTER" | "HIGHER_IS_BETTER";
    goodThreshold: number;
    displayMin: number;
    displayMax: number;
  };
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

// Markers we show on the dashboard compact panel, in display order.
// Four high-value markers: Testosterone, LDL, ApoB, HbA1c
const DASHBOARD_BLOOD_MARKERS = ["TESTOSTERONE", "LDL", "APO_B", "HBA1C"] as const;
type DashboardMarker = (typeof DASHBOARD_BLOOD_MARKERS)[number];

const DASHBOARD_BLOOD_LABELS: Record<string, string> = {
  TESTOSTERONE: "Testosterone",
  LDL: "LDL",
  APO_B: "ApoB",
  HBA1C: "HbA1c",
};

// Default reference ranges for markers extracted from lab reports
const DEFAULT_REFS: Record<DashboardMarker, { min: number; threshold: number; max: number; orientation: "LOWER_IS_BETTER" | "HIGHER_IS_BETTER" }> = {
  TESTOSTERONE: { min: 200, threshold: 300, max: 1200, orientation: "HIGHER_IS_BETTER" },
  LDL: { min: 0, threshold: 100, max: 200, orientation: "LOWER_IS_BETTER" },
  APO_B: { min: 0, threshold: 90, max: 150, orientation: "LOWER_IS_BETTER" },
  HBA1C: { min: 4, threshold: 5.7, max: 8, orientation: "LOWER_IS_BETTER" },
};

// Patterns to match marker names from lab reports
const BLOOD_MARKER_PATTERNS: { pattern: RegExp; marker: DashboardMarker }[] = [
  { pattern: /\btestosterone\b/i, marker: "TESTOSTERONE" },
  { pattern: /\bapob\b|\bapo[\s-]?b\b|\bapolipoprotein[\s-]?b\b/i, marker: "APO_B" },
  { pattern: /\bldl\b/i, marker: "LDL" },
  { pattern: /\bhba1c\b|\bhgba1c\b|\bhemoglobin\s*a1c\b|\bglycated\s*hemoglobin\b/i, marker: "HBA1C" },
  { pattern: /^a1c$/i, marker: "HBA1C" },
];

function normalizeBloodMarkerName(name: string): DashboardMarker | null {
  const trimmed = name.trim();
  for (const { pattern, marker } of BLOOD_MARKER_PATTERNS) {
    if (pattern.test(trimmed)) {
      return marker;
    }
  }
  return null;
}

type LatestBloodValue = {
  value: number;
  unit: string;
  sampleDate: string;
  refLow: number | null;
  refHigh: number | null;
  flag: "H" | "L" | null;
  source: "reading" | "report";
};

type HistoryPoint = { date: string; value: number };

async function loadBloodPanel(): Promise<BloodPanelData | null> {
  let readings: BloodReadingApi[] = [];
  let reports: BloodTestReport[] = [];

  try {
    [readings, reports] = await Promise.all([
      apiJson<BloodReadingApi[]>("/api/me/blood"),
      apiJson<BloodTestReport[]>("/api/me/blood/reports"),
    ]);
  } catch {
    return null;
  }

  if (readings.length === 0 && reports.length === 0) return null;

  // Filter to last year
  const oneYearAgo = new Date();
  oneYearAgo.setFullYear(oneYearAgo.getFullYear() - 1);
  const cutoffDate = oneYearAgo.toISOString().split("T")[0]!;

  // Build latest values and history from both readings and reports
  const latestByMarker = new Map<DashboardMarker, LatestBloodValue>();
  const historyByMarker = new Map<DashboardMarker, HistoryPoint[]>();

  // Initialize history arrays
  for (const m of DASHBOARD_BLOOD_MARKERS) {
    historyByMarker.set(m, []);
  }

  // Process manual readings
  for (const r of readings) {
    if (!DASHBOARD_BLOOD_MARKERS.includes(r.marker as DashboardMarker)) continue;
    const key = r.marker as DashboardMarker;

    // Add to history if within last year
    if (r.sampleDate >= cutoffDate) {
      historyByMarker.get(key)!.push({ date: r.sampleDate, value: r.value });
    }

    const existing = latestByMarker.get(key);
    if (!existing || existing.sampleDate < r.sampleDate) {
      latestByMarker.set(key, {
        value: r.value,
        unit: r.unit,
        sampleDate: r.sampleDate,
        refLow: r.reference.displayMin,
        refHigh: r.reference.goodThreshold,
        flag: null,
        source: "reading",
      });
    }
  }

  // Process extracted markers from reports
  for (const report of reports) {
    if (!report.sampleDate) continue;
    for (const m of report.markers) {
      if (m.value === null) continue;
      const canonicalName = normalizeBloodMarkerName(m.name);
      if (!canonicalName) continue;

      // Add to history if within last year
      if (report.sampleDate >= cutoffDate) {
        historyByMarker.get(canonicalName)!.push({ date: report.sampleDate, value: m.value });
      }

      const existing = latestByMarker.get(canonicalName);
      if (!existing || existing.sampleDate < report.sampleDate) {
        latestByMarker.set(canonicalName, {
          value: m.value,
          unit: m.unit ?? "",
          sampleDate: report.sampleDate,
          refLow: m.refRangeLow,
          refHigh: m.refRangeHigh,
          flag: m.flag,
          source: "report",
        });
      }
    }
  }

  // Sort and dedupe history
  for (const m of DASHBOARD_BLOOD_MARKERS) {
    const points = historyByMarker.get(m)!;
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
    historyByMarker.set(m, deduped);
  }

  if (latestByMarker.size === 0) return null;

  const markers: BloodPanelMarker[] = [];
  let latestDate: string | null = null;

  for (const m of DASHBOARD_BLOOD_MARKERS) {
    const r = latestByMarker.get(m);
    if (!r) continue;
    if (!latestDate || latestDate < r.sampleDate) latestDate = r.sampleDate;

    // Use default refs if not available from the data
    const defaultRef = DEFAULT_REFS[m];
    const displayMin = r.refLow ?? defaultRef.min;
    const goodThreshold = r.refHigh ?? defaultRef.threshold;
    const displayMax = defaultRef.max;
    const orientation = defaultRef.orientation;

    const span = displayMax - displayMin;
    const tickPct = Math.max(
      0,
      Math.min(100, ((r.value - displayMin) / span) * 100),
    );
    // Good fill renders the part of the bar that's "in the good zone".
    const goodLeftPct =
      orientation === "LOWER_IS_BETTER"
        ? 0
        : ((goodThreshold - displayMin) / span) * 100;
    const goodWidthPct =
      orientation === "LOWER_IS_BETTER"
        ? ((goodThreshold - displayMin) / span) * 100
        : 100 - ((goodThreshold - displayMin) / span) * 100;
    const onGoodSide =
      orientation === "LOWER_IS_BETTER"
        ? r.value <= goodThreshold
        : r.value >= goodThreshold;
    const dist = Math.abs(r.value - goodThreshold) / goodThreshold;
    const tone: "good" | "warn" | "alert" = onGoodSide
      ? "good"
      : dist < 0.15
        ? "warn"
        : "alert";

    markers.push({
      name: DASHBOARD_BLOOD_LABELS[m] ?? m,
      value: r.value.toFixed(2),
      unit: r.unit,
      tone,
      goodFillPct: goodWidthPct,
      goodLeftPct,
      tickPct,
      labels: {
        min: String(displayMin),
        threshold: String(goodThreshold),
        max: String(displayMax),
      },
      sparkline: historyByMarker.get(m),
      refLow: displayMin,
      refHigh: goodThreshold,
    });
  }

  return {
    date: latestDate ? formatShortDate(latestDate) : null,
    markers,
  };
}

function formatShortDate(iso: string): string {
  // "2026-05-22" → "MAY 22 · 2026"
  const d = new Date(iso + "T00:00:00Z");
  const month = d.toLocaleString("en-US", { month: "short", timeZone: "UTC" }).toUpperCase();
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${month} ${day} · ${d.getUTCFullYear()}`;
}

// Links the body-composition section title to /me/body-composition.
// Mirrors the SectionTitle look but adds a hover affordance + arrow.
function BodyCompositionTitle() {
  return (
    <Link
      href="/me/body-composition"
      className="group inline-flex items-center gap-2.5 hover:text-accent-dim"
    >
      <span
        aria-hidden
        className="inline-block h-3.5 w-[3px] rounded-[2px] bg-accent"
      />
      <span className="text-[14px] font-medium tracking-[-0.01em] text-primary group-hover:text-accent-dim">
        Body composition
      </span>
      <span
        aria-hidden
        className="font-mono text-[11px] text-tertiary opacity-0 transition-opacity group-hover:opacity-100"
      >
        →
      </span>
    </Link>
  );
}

// Load today's scheduled medication doses
async function loadTodaysDoses(): Promise<TodaysDose[]> {
  try {
    return await apiJson<TodaysDose[]>("/api/me/medications/today");
  } catch {
    return [];
  }
}
