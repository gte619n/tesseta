import { apiJson } from "./api";
import type {
  WorkoutStats,
  E1rmHistory,
  SessionDetailResponse,
} from "./types/workout-stats";

// Server-only HTTP helpers for the web Overview read-model
// (IMPL-WEB-WORKOUT-01 §5). Do not import from client components — apiJson
// reads server env + the Auth.js session. The consolidated summary powers the
// hero + cards; the e1RM history is fetched lazily when a lift is charted; the
// session detail backs the per-session page.

/**
 * The consolidated Overview bundle: streak, weekly volume series, consistency
 * heatmap, recent PRs, and the lift lists the strength chart needs. `weeks`
 * sizes the series (backend clamps to 4..104).
 */
export function getWorkoutStats(weeks = 26): Promise<WorkoutStats> {
  return apiJson<WorkoutStats>(`/api/me/workout-stats?weeks=${weeks}`);
}

/**
 * A single lift's estimated-1RM curve + current belief, fetched lazily when a
 * lift is selected. Unknown ids come back 200 with empty points.
 */
export function getE1rmHistory(exerciseId: string): Promise<E1rmHistory> {
  return apiJson<E1rmHistory>(
    `/api/me/workout-stats/e1rm-history?exerciseId=${encodeURIComponent(exerciseId)}`,
  );
}

/**
 * One performed session in full for the detail page — the block/exercise/set
 * tree plus PR set keys and prev/next neighbors.
 */
export function getSessionDetail(
  programId: string,
  scheduledId: string,
): Promise<SessionDetailResponse> {
  return apiJson<SessionDetailResponse>(
    `/api/me/workout-programs/${programId}/sessions/${scheduledId}`,
  );
}
