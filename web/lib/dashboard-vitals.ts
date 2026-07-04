import { fetchDailyMetrics, type DailyMetric } from "@/lib/daily-metrics-api";

// Builders for the dashboard's top-row daily-vitals tiles (Resting HR / HRV /
// Sleep / Steps). Extracted from app/page.tsx, together with `loadDailyMetrics`
// which wires them to the daily-metrics endpoint.

// One top-row stat tile's data (a StatCard's props). Formerly a fixture type.
export type VitalDelta = {
  direction: "up" | "down";
  value: string;
  window: string;
  tone: "good" | "alert";
};

export type Vital = {
  label: string;
  icon: string;
  value: string;
  unit?: string;
  delta?: VitalDelta;
  pill?: { label: string; tone: "good" | "warn" | "alert" };
  // 9-point sparkline path, normalized to a 48×20 viewBox.
  sparkline: string;
};

const FLAT_SPARKLINE = "0,10 6,10 12,10 18,10 24,10 30,10 36,10 42,10 48,10";
const DAY_MS = 24 * 60 * 60 * 1000;

// A "no data yet" tile: the metric's label/icon with an em-dash value and a
// flat sparkline — shown instead of fake numbers when a source has no readings.
export function emptyVital(label: string, icon: string, unit?: string): Vital {
  return {
    label,
    icon,
    value: "—",
    ...(unit ? { unit } : {}),
    sparkline: FLAT_SPARKLINE,
  };
}

export type MetricSpec = {
  field: "restingHeartRate" | "hrvMs" | "sleepMinutes" | "steps";
  label: string;
  icon: string;
  unit?: string;
  higherIsBetter: boolean;
  // Optional unit conversion applied to every raw value (e.g. minutes → hours).
  transform?: (raw: number) => number;
  format: (value: number) => string;
  formatDelta: (delta: number) => string;
  // Fixture to fall back to when there's no data, or null to render a "—" tile.
  emptyFixture: Vital | null;
};

type VitalDeltaLocal = NonNullable<Vital["delta"]>;

export function buildVital(rows: DailyMetric[], spec: MetricSpec): Vital {
  // Series of non-null (transformed) values with their dates, oldest → newest.
  const points: { date: string; value: number }[] = [];
  for (const row of rows) {
    const raw = row[spec.field];
    if (raw === null) continue;
    points.push({
      date: row.date,
      value: spec.transform ? spec.transform(raw) : raw,
    });
  }

  if (points.length === 0) {
    if (spec.emptyFixture) return spec.emptyFixture;
    return {
      label: spec.label,
      icon: spec.icon,
      value: "—",
      ...(spec.unit ? { unit: spec.unit } : {}),
      sparkline: FLAT_SPARKLINE,
    };
  }

  const latest = points[points.length - 1]!;
  const series = points.map((p) => p.value);

  // Delta = latest minus the mean of values before latest within the prior
  // 7 days (the 7 days preceding the latest sample's date).
  const latestTime = new Date(latest.date + "T00:00:00Z").getTime();
  const priorCutoff = latestTime - 7 * DAY_MS;
  const prior = points
    .slice(0, points.length - 1)
    .filter((p) => {
      const t = new Date(p.date + "T00:00:00Z").getTime();
      return t >= priorCutoff && t < latestTime;
    })
    .map((p) => p.value);

  let delta: VitalDeltaLocal | undefined;
  if (prior.length > 0) {
    const mean = prior.reduce((a, b) => a + b, 0) / prior.length;
    const diff = latest.value - mean;
    const direction: "up" | "down" = diff >= 0 ? "up" : "down";
    // For higher-is-better metrics an increase is good; for lower-is-better
    // (Resting HR) a decrease is good.
    const tone: "good" | "alert" = spec.higherIsBetter
      ? diff >= 0
        ? "good"
        : "alert"
      : diff <= 0
        ? "good"
        : "alert";
    delta = {
      direction,
      value: spec.formatDelta(Math.abs(diff)),
      window: "7d",
      tone,
    };
  }

  return {
    label: spec.label,
    icon: spec.icon,
    value: spec.format(latest.value),
    ...(spec.unit ? { unit: spec.unit } : {}),
    ...(delta ? { delta } : {}),
    sparkline: metricSparkline(series),
  };
}

// ── Daily metrics loader (top-row vitals) ────────────────────────────────
// Wires the Resting HR / HRV / Sleep / Steps tiles to the daily-metrics
// endpoint. Each tile takes the series of non-null values in the window
// (oldest → newest): the latest value is shown, the delta is latest minus the
// mean of prior values within the preceding 7 days, and the sparkline is built
// from up to the last 9 non-null values. Every tile renders a "—" placeholder
// when the user has no data for it (no wearable connected) rather than fake
// numbers.

export type DailyVitals = {
  restingHr: Vital;
  hrv: Vital;
  sleep: Vital;
  steps: Vital;
};

export async function loadDailyMetrics(): Promise<DailyVitals> {
  const rows = await fetchDailyMetrics();
  // Oldest → newest so "latest" is the final element.
  const sorted = [...rows].sort((a, b) => a.date.localeCompare(b.date));

  return {
    restingHr: buildVital(sorted, {
      field: "restingHeartRate",
      label: "Resting HR",
      icon: "heart",
      unit: "bpm",
      higherIsBetter: false,
      format: (v) => String(Math.round(v)),
      formatDelta: (d) => String(Math.round(d)),
      emptyFixture: null,
    }),
    hrv: buildVital(sorted, {
      field: "hrvMs",
      label: "HRV",
      icon: "activity-heartbeat",
      unit: "ms",
      higherIsBetter: true,
      format: (v) => String(Math.round(v)),
      formatDelta: (d) => String(Math.round(d)),
      emptyFixture: null,
    }),
    sleep: buildVital(sorted, {
      field: "sleepMinutes",
      label: "Sleep",
      icon: "moon",
      unit: "h",
      higherIsBetter: true,
      // Stored as minutes; display hours to 1 decimal.
      transform: (v) => v / 60,
      format: (v) => v.toFixed(1),
      formatDelta: (d) => d.toFixed(1),
      emptyFixture: null,
    }),
    steps: buildVital(sorted, {
      field: "steps",
      label: "Steps",
      icon: "walk",
      higherIsBetter: true,
      format: (v) => Math.round(v).toLocaleString("en-US"),
      formatDelta: (d) => Math.round(d).toLocaleString("en-US"),
      emptyFixture: null,
    }),
  };
}

// Build a 9-point 48×20 sparkline from up to the last 9 values, min–max
// normalized. Higher value → lower y (top of the viewBox), mirroring the
// weight sparkline helper.
function metricSparkline(series: number[]): string {
  const tail = series.slice(-9);
  if (tail.length === 0) return FLAT_SPARKLINE;
  const min = Math.min(...tail);
  const max = Math.max(...tail);
  const range = max - min || 1;
  const N = tail.length;
  return tail
    .map((y, i) => {
      const x = N === 1 ? 0 : (i * 48) / (N - 1);
      const yPx = 2 + ((max - y) / range) * 16;
      return `${x.toFixed(0)},${yPx.toFixed(0)}`;
    })
    .join(" ");
}
