import { apiJson } from "@/lib/api";
import type {
  BloodPanelData,
  BloodPanelMarker,
} from "@/components/dashboard/BloodPanel";
import {
  DASHBOARD_BLOOD_MARKERS,
  type DashboardMarker,
  DASHBOARD_BLOOD_LABELS,
  DEFAULT_REFS,
  normalizeBloodMarkerName,
} from "@/lib/blood-markers";

// Dashboard blood-panel loader. Merges manual readings (/api/me/blood) with
// AI-extracted report markers (/api/me/blood/reports) into the compact
// BloodPanel card model: per-marker latest value, tone, good-zone bar geometry,
// and a de-duped one-year history sparkline. Extracted from app/page.tsx.

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

export async function loadBloodPanel(): Promise<BloodPanelData | null> {
  let readings: BloodReadingApi[] = [];
  let reports: BloodTestReport[] = [];

  try {
    // Reports (AI-extracted uploads) are independent of manual readings and may
    // be unavailable; don't let that hide the readings we do have.
    [readings, reports] = await Promise.all([
      apiJson<BloodReadingApi[]>("/api/me/blood"),
      apiJson<BloodTestReport[]>("/api/me/blood/reports").catch(() => [] as BloodTestReport[]),
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
