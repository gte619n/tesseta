// Fixture data for the dashboard. Values lifted from the mockup HTML so the
// implementation is a 1:1 visual match. When the fixture generator script
// (IMPL-02) lands, this file becomes its output.

export const user = {
  initials: "EG",
  name: "Evan Glazier",
  role: "CEO",
};

export const todayHeader = {
  // Captured from the mockup: TUE · MAY 20 · 07:42 · ATL
  weekday: "TUE",
  date: "MAY 20",
  time: "07:42",
  tz: "ATL",
  greeting: "Good morning, Evan",
};

export type VitalDelta = {
  direction: "up" | "down";
  value: string;
  window: string;
  tone: "good" | "alert";
};

export type Vital = {
  label: string;
  icon: string; // tabler icon name (no `ti-` prefix)
  value: string;
  unit?: string;
  delta?: VitalDelta;
  pill?: { label: string; tone: "good" | "warn" | "alert" };
  // 9-point sparkline path, normalized to a 48×20 viewBox.
  sparkline: string;
};

export const vitals: Vital[] = [
  {
    label: "Weight",
    icon: "scale",
    value: "189.2",
    unit: "lb",
    delta: { direction: "down", value: "0.4", window: "7d", tone: "good" },
    sparkline: "0,12 6,10 12,13 18,9 24,11 30,8 36,9 42,6 48,7",
  },
  {
    label: "HRV",
    icon: "activity-heartbeat",
    value: "62",
    unit: "ms",
    delta: { direction: "up", value: "3", window: "7d", tone: "good" },
    sparkline: "0,14 6,11 12,12 18,8 24,10 30,7 36,6 42,5 48,4",
  },
  {
    label: "Resting HR",
    icon: "heart",
    value: "51",
    unit: "bpm",
    delta: { direction: "down", value: "1", window: "7d", tone: "good" },
    sparkline: "0,7 6,9 12,8 18,10 24,9 30,11 36,10 42,12 48,13",
  },
  {
    label: "Readiness",
    icon: "flame",
    value: "84",
    unit: "%",
    pill: { label: "Primed", tone: "good" },
    sparkline: "0,9 6,8 12,11 18,7 24,8 30,6 36,5 42,7 48,5",
  },
];

// 52-point weight series matching the mockup. Starts at 192.8 lb, ends at
// 189.2 lb. Pad to xMin=24 / xMax=W-12 in the chart math.
export const weightSeries = [
  192.8, 192.6, 193.1, 192.9, 192.4, 192.7, 192.3, 192.0, 192.5, 191.8, 191.6,
  192.1, 191.4, 191.7, 191.2, 190.9, 191.4, 190.8, 190.5, 191.0, 190.6, 190.3,
  190.8, 190.4, 190.1, 190.5, 190.7, 190.2, 190.0, 190.4, 189.8, 190.1, 189.6,
  189.9, 189.4, 189.7, 190.0, 189.5, 189.3, 189.6, 189.9, 189.4, 189.2, 189.5,
  189.1, 189.4, 189.8, 189.3, 189.0, 189.4, 189.7, 189.2,
];

export const bodyComp = {
  primary: { value: "189.2", unit: "lb", delta: "↓ 3.6 vs 90d avg" },
  secondary: [
    { value: "17.4", unit: "% fat", delta: "↓ 0.8 pts" },
    { value: "156.3", unit: "lb lean", delta: "↑ 1.2" },
  ],
  range: { active: "90d", options: ["30d", "90d", "1y", "All"] as const },
  axis: {
    yLabels: ["194", "192", "190", "188"],
    xLabels: [
      { x: 32, label: "FEB 20" },
      { x: 180, label: "MAR 22" },
      { x: 335, label: "APR 20" },
      { x: 500, label: "MAY 20" },
    ],
  },
};

export type BloodMarker = {
  name: string;
  value: string;
  unit: string;
  tone: "good" | "warn" | "alert";
  goodFillPct: number;
  tickPct: number;
  labels: { min: string; threshold: string; max: string };
};

const bloodMarkers: BloodMarker[] = [
  {
    name: "LDL",
    value: "112",
    unit: "mg/dL",
    tone: "warn",
    goodFillPct: 55,
    tickPct: 62,
    labels: { min: "0", threshold: "100", max: "200" },
  },
  {
    name: "ApoB",
    value: "92",
    unit: "mg/dL",
    tone: "warn",
    goodFillPct: 45,
    tickPct: 51,
    labels: { min: "0", threshold: "90", max: "180" },
  },
  {
    name: "HbA1c",
    value: "5.2",
    unit: "%",
    tone: "good",
    goodFillPct: 57,
    tickPct: 33,
    labels: { min: "4", threshold: "5.7", max: "7" },
  },
  {
    name: "hs-CRP",
    value: "0.4",
    unit: "mg/L",
    tone: "good",
    goodFillPct: 33,
    tickPct: 13,
    labels: { min: "0", threshold: "1", max: "3" },
  },
];

export const bloodPanel = {
  date: "OCT 14 · 2025",
  markers: bloodMarkers,
};

export const today = {
  status: "In progress",
  calories: { current: "1,247", target: "2,800", pct: 45 },
  macros: [
    { label: "Protein", value: "112", unit: "g", pct: 56, color: "var(--color-accent)" },
    { label: "Carbs", value: "98", unit: "g", pct: 35, color: "var(--color-good-alt)" },
    { label: "Fat", value: "42", unit: "g", pct: 48, color: "var(--color-muted)" },
  ],
  workout: {
    title: "Pull Day · 06:15",
    meta: "52 min · 14,200 lb · 142 HR",
    metaPhone: "52 min · 14,200 lb",
    pill: "Done",
  },
};

export type LogEntry = {
  icon: string;
  tone: "activity" | "neutral";
  title: string;
  meta?: string;
  metaPhone?: string;
  metaFoldable?: string;
  time: string;
};

export const recent: LogEntry[] = [
  {
    icon: "barbell",
    tone: "activity",
    title: "Pull Day completed",
    meta: "5 exercises · 18 sets · 14,200 lb",
    metaPhone: "5 exercises · 18 sets",
    metaFoldable: "Pull Day completed · 5 exercises · 18 sets",
    time: "07:08",
  },
  {
    icon: "scale",
    tone: "neutral",
    title: "Weighed in · 189.2 lb",
    meta: "Aria Air · post-workout",
    metaPhone: "Aria Air",
    metaFoldable: "Weighed in · 189.2 lb · Aria Air",
    time: "06:14",
  },
  {
    icon: "pill",
    tone: "activity",
    title: "Rosuvastatin · 10 mg",
    meta: "94% adherence · 30d",
    metaPhone: "94% · 30d",
    metaFoldable: "Rosuvastatin · 10 mg · 94% adherence 30d",
    time: "05:58",
  },
  {
    icon: "moon",
    tone: "neutral",
    title: "Sleep · 7h 42m",
    meta: "Score 87 · 1h 38m REM · 1h 22m deep",
    metaFoldable: "Sleep · 7h 42m · Score 87 · REM 1h 38m",
    time: "05:30",
  },
];

// `active` is computed from the current pathname at render time; the
// only declarative state here is label / icon / href / optional badge.
export const navItems = [
  { label: "Dashboard", icon: "layout-dashboard", href: "/" },
  { label: "Goals", icon: "route", href: "/me/goals" },
  { label: "Body", icon: "body-scan", href: "/me/body-composition" },
  { label: "Blood", icon: "droplet", href: "/me/blood" },
  { label: "Workouts", icon: "barbell", href: "/me/workouts" },
  { label: "Meds", icon: "pill", href: "/me/meds" },
  { label: "Nutrition", icon: "bowl", href: "/me/nutrition" },
  { label: "Insights", icon: "sparkles", href: "/me/insights" },
];
