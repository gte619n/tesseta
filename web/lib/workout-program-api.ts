import { apiFetch, apiJson, BackendError, send } from "./api";
import type {
  WorkoutProgramResponse,
  WorkoutProgramDeepResponse,
  ScheduledWorkoutResponse,
  WorkoutHistorySummary,
  CreateProgramRequest,
  UpdateProgramRequest,
  CompleteSessionRequest,
  WorkoutProgramChatThread,
  WorkoutProgramChatMessage,
} from "./types/workout-program";
import type { TrtContext } from "./types/trt";

// Server-only HTTP helpers for Workout Programs (IMPL-15). Do not import from
// client components — apiFetch reads server env + the Auth.js session. The SSE
// chat send goes through the route handler in app/api/workout-programs/chat
// (the browser needs to read the stream); commit + reads live here. The shared
// `send` JSON mutation helper lives in lib/api.ts.

// ── Reads ────────────────────────────────────────────────────────────

export function listPrograms(): Promise<WorkoutProgramResponse[]> {
  return apiJson<WorkoutProgramResponse[]>("/api/me/workout-programs");
}

export function getProgramDeep(
  programId: string,
): Promise<WorkoutProgramDeepResponse> {
  return apiJson<WorkoutProgramDeepResponse>(
    `/api/me/workout-programs/${programId}`,
  );
}

export function getProgramCalendar(
  programId: string,
  from: string,
  to: string,
): Promise<ScheduledWorkoutResponse[]> {
  return apiJson<ScheduledWorkoutResponse[]>(
    `/api/me/workout-programs/${programId}/calendar?from=${from}&to=${to}`,
  );
}

// Performed sessions across every program, newest first (Workout History).
export function getWorkoutHistory(): Promise<ScheduledWorkoutResponse[]> {
  return apiJson<ScheduledWorkoutResponse[]>("/api/me/workout-history");
}

// Lightweight counts for the Workouts hub — avoids loading full sessions.
export function getWorkoutHistorySummary(): Promise<WorkoutHistorySummary> {
  return apiJson<WorkoutHistorySummary>("/api/me/workout-history/summary");
}

// ── Mutations ────────────────────────────────────────────────────────

export function createProgram(
  input: CreateProgramRequest,
): Promise<WorkoutProgramDeepResponse> {
  return send<WorkoutProgramDeepResponse>(
    "/api/me/workout-programs",
    "POST",
    input,
  );
}

export function updateProgram(
  programId: string,
  input: UpdateProgramRequest,
): Promise<WorkoutProgramDeepResponse> {
  return send<WorkoutProgramDeepResponse>(
    `/api/me/workout-programs/${programId}`,
    "PATCH",
    input,
  );
}

export function deleteProgram(programId: string): Promise<void> {
  return send<void>(`/api/me/workout-programs/${programId}`, "DELETE");
}

export function validateProgram(programId: string): Promise<string[]> {
  return send<string[]>(`/api/me/workout-programs/${programId}/validate`, "POST");
}

export function activateProgram(
  programId: string,
): Promise<ScheduledWorkoutResponse[]> {
  return send<ScheduledWorkoutResponse[]>(
    `/api/me/workout-programs/${programId}/activate`,
    "POST",
  );
}

// Idempotent completion upsert (ADR-0012 / IMPL-17 D1–D2): mark a scheduled
// session COMPLETED or SKIPPED and replace its logged actuals. Repeat PUTs
// are safe — the backend re-runs the Workout/aggregate fan-out each time.
export function completeSession(
  programId: string,
  scheduledId: string,
  input: CompleteSessionRequest,
): Promise<ScheduledWorkoutResponse> {
  return send<ScheduledWorkoutResponse>(
    `/api/me/workout-programs/${programId}/sessions/${scheduledId}`,
    "PUT",
    input,
  );
}

// ── Chat threads + history ───────────────────────────────────────────
//
// The SSE send itself goes through app/api/workout-programs/chat (the browser
// needs to read the stream). Thread listing, history, and commit are plain
// JSON, so they live here as server-only helpers — mirroring goals-api.ts.

export function listProgramChatThreads(): Promise<WorkoutProgramChatThread[]> {
  return apiJson<WorkoutProgramChatThread[]>(
    "/api/me/workout-programs/chat/threads",
  );
}

export function getProgramChatMessages(
  threadId: string,
): Promise<WorkoutProgramChatMessage[]> {
  return apiJson<WorkoutProgramChatMessage[]>(
    `/api/me/workout-programs/chat/${threadId}`,
  );
}

export function deleteProgramChatThread(threadId: string): Promise<void> {
  return send<void>(
    `/api/me/workout-programs/chat/threads/${threadId}`,
    "DELETE",
  );
}

// ── TRT / monitoring panel (IMPL-18 / ADR-0015) ──────────────────────
//
// The user's relevant labs (latest + trend, ref ranges, status) and any
// danger-flags for the TRT decision-support surface in the designer chat. Read
// server-side and passed to the chat component; the browser can also hit the
// app/api proxy route for an on-mount fetch.
export function getTrtContext(): Promise<TrtContext> {
  return apiJson<TrtContext>("/api/me/workout-programs/chat/trt-context");
}

// ── Chat commit ──────────────────────────────────────────────────────
//
// The backend returns 201 with the deep program on success, or 422 with
// { issues: [] } when validation fails — we distinguish the two so the UI can
// re-render inline issues, matching the Goals commit contract. The commit is
// scoped to the thread (its fixed schedule comes from the thread).

export type CommitProgramResult =
  | { ok: true; program: WorkoutProgramDeepResponse }
  | { ok: false; issues: string[] };

export async function commitProgramProposal(
  threadId: string,
  input: CreateProgramRequest,
): Promise<CommitProgramResult> {
  const res = await apiFetch(
    `/api/me/workout-programs/chat/${threadId}/commit`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    },
  );
  if (res.ok) {
    const program = (await res.json()) as WorkoutProgramDeepResponse;
    return { ok: true, program };
  }
  if (res.status === 422) {
    const body = (await res.json().catch(() => ({ issues: [] }))) as {
      issues?: string[];
    };
    return { ok: false, issues: body.issues ?? [] };
  }
  throw new BackendError(`commit returned ${res.status}`, res.status);
}
