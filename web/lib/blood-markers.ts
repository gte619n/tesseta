// Dashboard blood-marker config: the high-value markers shown on the compact
// dashboard panel, their labels, default reference ranges, and name
// normalization for markers extracted from uploaded lab reports.

/** Markers shown on the dashboard compact panel, in display order. */
export const DASHBOARD_BLOOD_MARKERS = ["TESTOSTERONE", "LDL", "APO_B", "HBA1C"] as const;
export type DashboardMarker = (typeof DASHBOARD_BLOOD_MARKERS)[number];

export const DASHBOARD_BLOOD_LABELS: Record<string, string> = {
  TESTOSTERONE: "Testosterone",
  LDL: "LDL",
  APO_B: "ApoB",
  HBA1C: "HbA1c",
};

/** Default reference ranges for markers extracted from lab reports. */
export const DEFAULT_REFS: Record<
  DashboardMarker,
  { min: number; threshold: number; max: number; orientation: "LOWER_IS_BETTER" | "HIGHER_IS_BETTER" }
> = {
  TESTOSTERONE: { min: 200, threshold: 300, max: 1200, orientation: "HIGHER_IS_BETTER" },
  LDL: { min: 0, threshold: 100, max: 200, orientation: "LOWER_IS_BETTER" },
  APO_B: { min: 0, threshold: 90, max: 150, orientation: "LOWER_IS_BETTER" },
  HBA1C: { min: 4, threshold: 5.7, max: 8, orientation: "LOWER_IS_BETTER" },
};

// Patterns to match marker names from lab reports.
const BLOOD_MARKER_PATTERNS: { pattern: RegExp; marker: DashboardMarker }[] = [
  { pattern: /\btestosterone\b/i, marker: "TESTOSTERONE" },
  { pattern: /\bapob\b|\bapo[\s-]?b\b|\bapolipoprotein[\s-]?b\b/i, marker: "APO_B" },
  { pattern: /\bldl\b/i, marker: "LDL" },
  { pattern: /\bhba1c\b|\bhgba1c\b|\bhemoglobin\s*a1c\b|\bglycated\s*hemoglobin\b/i, marker: "HBA1C" },
  { pattern: /^a1c$/i, marker: "HBA1C" },
];

/** Map a raw lab-report marker name to a dashboard marker, or null if unknown. */
export function normalizeBloodMarkerName(name: string): DashboardMarker | null {
  const trimmed = name.trim();
  for (const { pattern, marker } of BLOOD_MARKER_PATTERNS) {
    if (pattern.test(trimmed)) {
      return marker;
    }
  }
  return null;
}
