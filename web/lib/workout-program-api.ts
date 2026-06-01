import { apiFetch, apiJson, BackendError } from "./api";
import type {
  WorkoutProgramResponse,
  WorkoutProgramDeepResponse,
  ScheduledWorkoutResponse,
  CreateProgramRequest,
  UpdateProgramRequest,
} from "./types/workout-program";

// Server-only HTTP helpers for Workout Programs (IMPL-15). Do not import from
// client components — apiFetch reads server env + the Auth.js session. The SSE
// chat send goes through the route handler in app/api/workout-programs/chat
// (the browser needs to read the stream); commit + reads live here.

async function send<T>(
  path: string,
  method: "POST" | "PATCH" | "PUT" | "DELETE",
  body?: unknown,
): Promise<T> {
  const res = await apiFetch(path, {
    method,
    ...(body !== undefined
      ? {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      : {}),
  });
  if (!res.ok) {
    throw new BackendError(`${method} ${path} returned ${res.status}`, res.status);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

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

// ── Chat commit ──────────────────────────────────────────────────────
//
// The backend returns 201 with the deep program on success, or 422 with
// { issues: [] } when validation fails — we distinguish the two so the UI can
// re-render inline issues, matching the Goals commit contract.

export type CommitProgramResult =
  | { ok: true; program: WorkoutProgramDeepResponse }
  | { ok: false; issues: string[] };

export async function commitProgramProposal(
  input: CreateProgramRequest,
): Promise<CommitProgramResult> {
  const res = await apiFetch("/api/me/workout-programs/chat/commit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
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
