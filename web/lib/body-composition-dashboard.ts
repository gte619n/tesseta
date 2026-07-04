import { apiJson } from "@/lib/api";
import type { Reading } from "@/lib/types/body-composition";
import type { WeightStat } from "@/components/dashboard/WeightStatCard";

// Dashboard body-composition loader. Turns the raw weigh-in / body-fat readings
// (/api/me/body-composition) into the view model shared by the top-row Weight
// StatCard and the BodyCompositionCard: latest weight, 90-day delta, lean-mass
// derivation, and the weight chart series/axis/labels. Extracted from
// app/page.tsx.

const KG_TO_LB = 2.20462;
const NINETY_DAYS_MS = 90 * 24 * 60 * 60 * 1000;

export type BodyCompositionView = {
  // Latest weight in canonical lb; formatted client-side per unit pref.
  primaryWeightLb: number;
  // Signed change vs the 90d average, in lb (negative = down).
  primaryDeltaLb: number | null;
  // Body fat % (already unit-agnostic).
  bodyFatPercent: number | null;
  // Lean mass in canonical lb; formatted client-side per unit pref.
  leanMassLb: number | null;
  // Weight series in lb, oldest → newest.
  series: number[];
  yMin: number;
  yMax: number;
  xLabels: { x: number; label: string }[];
  // Raw lb data for the top-row Weight StatCard (formatted client-side).
  weightStat: WeightStat | null;
  // sampleTime (ISO) of the most recent weigh-in driving primaryWeightLb.
  lastUpdated: string;
};

export async function loadBodyComposition(): Promise<BodyCompositionView | null> {
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
  const weightStat: WeightStat = {
    label: "Weight",
    icon: "scale",
    valueLb: latestWeight,
    sparkline: weightSparkline(series),
    delta:
      sevenDayDelta !== null
        ? {
            deltaLb: sevenDayDelta,
            window: "7d",
            // Weight loss is typically the goal in this app's context; if
            // the user wants weight gain (cut/bulk cycles), this can grow
            // into a per-user preference later.
            tone: sevenDayDelta <= 0 ? "good" : "alert",
          }
        : undefined,
  };

  return {
    primaryWeightLb: latestWeight,
    primaryDeltaLb: delta,
    bodyFatPercent: latestBodyFat ? latestBodyFat.value : null,
    leanMassLb,
    series,
    yMin,
    yMax,
    xLabels,
    weightStat,
    // weightsAll is sorted oldest → newest, so the last entry is the freshest.
    lastUpdated: weightsAll[weightsAll.length - 1]!.sampleTime,
  };
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
