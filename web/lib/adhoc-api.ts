import { apiJson, send } from "./api";
import type { ScheduledWorkoutResponse } from "./types/workout-program";
import type {
  AdHocSort,
  AdHocWorkoutResponse,
  AdHocWorkoutSummary,
  GenerateAdHocRequest,
  GenerateAdHocResponse,
  LogAdHocRunRequest,
  SaveAdHocRequest,
  UpdateAdHocRequest,
} from "./types/adhoc";

// Ad-hoc workout library API client (IMPL-ADHOC-01). Server-side only, like the
// program client — apiJson/send read the Auth.js session's bearer.

const BASE = "/api/me/adhoc-workouts";

export type ListAdHocOpts = {
  includeArchived?: boolean;
  tag?: string;
  q?: string;
  maxDurationMin?: number;
  sort?: AdHocSort;
  pinnedFirst?: boolean;
};

export function listAdHocWorkouts(
  opts: ListAdHocOpts = {},
): Promise<AdHocWorkoutSummary[]> {
  const params = new URLSearchParams();
  if (opts.includeArchived) params.set("includeArchived", "true");
  if (opts.tag) params.set("tag", opts.tag);
  if (opts.q) params.set("q", opts.q);
  if (opts.maxDurationMin != null) {
    params.set("maxDurationMin", String(opts.maxDurationMin));
  }
  if (opts.sort) params.set("sort", opts.sort);
  if (opts.pinnedFirst === false) params.set("pinnedFirst", "false");
  const qs = params.toString();
  return apiJson<AdHocWorkoutSummary[]>(`${BASE}${qs ? `?${qs}` : ""}`);
}

export function getAdHocWorkout(
  adhocId: string,
): Promise<AdHocWorkoutResponse> {
  return apiJson<AdHocWorkoutResponse>(`${BASE}/${adhocId}`);
}

export function generateAdHocWorkout(
  body: GenerateAdHocRequest,
): Promise<GenerateAdHocResponse> {
  return send<GenerateAdHocResponse>(`${BASE}/generate`, "POST", body);
}

export function createAdHocWorkout(
  body: SaveAdHocRequest,
): Promise<AdHocWorkoutResponse> {
  return send<AdHocWorkoutResponse>(BASE, "POST", body);
}

export function updateAdHocWorkout(
  adhocId: string,
  body: UpdateAdHocRequest,
): Promise<AdHocWorkoutResponse> {
  return send<AdHocWorkoutResponse>(`${BASE}/${adhocId}`, "PATCH", body);
}

export function archiveAdHocWorkout(adhocId: string): Promise<void> {
  return send<void>(`${BASE}/${adhocId}`, "DELETE");
}

export function restoreAdHocWorkout(
  adhocId: string,
): Promise<AdHocWorkoutResponse> {
  return send<AdHocWorkoutResponse>(`${BASE}/${adhocId}/restore`, "POST");
}

// Terminal run upsert — `sessionId` is client-minted ("aws_…"); the first call
// materializes the run's snapshot from the template and records the outcome.
export function logAdHocRun(
  adhocId: string,
  sessionId: string,
  body: LogAdHocRunRequest,
): Promise<ScheduledWorkoutResponse> {
  return send<ScheduledWorkoutResponse>(
    `${BASE}/${adhocId}/sessions/${sessionId}`,
    "PUT",
    body,
  );
}

// A client-minted ad-hoc run id. crypto.randomUUID is available in the browser
// and in the Node runtime the route handlers use.
export function newAdHocSessionId(): string {
  const uuid =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2);
  return `aws_${uuid.replace(/-/g, "").slice(0, 12)}`;
}
