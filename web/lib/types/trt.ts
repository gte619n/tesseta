// Wire types for the TRT / monitoring-panel surface in the program-designer
// chat (IMPL-18 / ADR-0015). Mirrors the LOCKED backend contract for
// `GET /api/me/workout-programs/chat/trt-context`. Import-safe from client and
// server.

export type TrtMarkerTrend = "RISING" | "FALLING" | "STABLE" | "UNKNOWN";
export type TrtMarkerStatus = "LOW" | "IN_RANGE" | "HIGH" | "WATCH" | "UNKNOWN";

// One marker in the monitoring panel (testosterone, estradiol, hematocrit/Hgb,
// lipids, PSA, …): latest value vs. reference range, with trend + status.
export type TrtMarker = {
  name: string;
  label: string;
  value: number | null;
  unit: string | null;
  refLow: number | null;
  refHigh: number | null;
  sampleDate: string | null;
  trend: TrtMarkerTrend;
  status: TrtMarkerStatus;
};

export type DangerFlagSeverity = "WARNING" | "DANGER";

// A mandatory hard alert raised when a marker crosses a risk threshold
// (IMPL-18 S6e), urging clinician contact regardless of the question asked.
export type DangerFlag = {
  marker: string;
  severity: DangerFlagSeverity;
  message: string;
};

// GET /api/me/workout-programs/chat/trt-context response.
export type TrtContext = {
  onTrt: boolean;
  markers: TrtMarker[];
  dangerFlags: DangerFlag[];
};
