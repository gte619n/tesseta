import { apiFetch, apiJson, BackendError, send } from "./api";
import type {
  ExerciseResponse,
  CreateExerciseRequest,
  UpdateExerciseRequest,
  FrameSpec,
  PlanResponse,
} from "./types/exercise";
import type { Equipment } from "./types/gym";

// Server-only HTTP helpers for the Exercise catalog (IMPL-14). Do not import
// from client components — apiFetch reads server env + the Auth.js session.
// Mirrors gym-api.ts / drug-admin-api.ts conventions. The shared `send` JSON
// mutation helper lives in lib/api.ts.

// ── Public reads (used to hydrate exercise pickers etc.) ─────────────

export function getExercise(exerciseId: string): Promise<ExerciseResponse> {
  return apiJson<ExerciseResponse>(`/api/exercises/${exerciseId}`);
}

export function getAvailableExercises(
  locationId: string,
): Promise<ExerciseResponse[]> {
  return apiJson<ExerciseResponse[]>(
    `/api/exercises/available?locationId=${encodeURIComponent(locationId)}`,
  );
}

// ── Admin reads ──────────────────────────────────────────────────────

export function getAdminExerciseCatalog(): Promise<ExerciseResponse[]> {
  return apiJson<ExerciseResponse[]>("/api/admin/exercises/catalog");
}

export function getAdminExerciseReview(): Promise<ExerciseResponse[]> {
  return apiJson<ExerciseResponse[]>("/api/admin/exercises/review");
}

// ── Equipment catalog (for the requirement picker) ───────────────────

export function searchEquipment(search?: string): Promise<Equipment[]> {
  const qs = search?.trim()
    ? `?search=${encodeURIComponent(search.trim())}`
    : "";
  return apiJson<Equipment[]>(`/api/equipment${qs}`);
}

// ── Admin mutations ──────────────────────────────────────────────────

export function createExercise(
  input: CreateExerciseRequest,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>("/api/admin/exercises", "POST", input);
}

export function updateExercise(
  exerciseId: string,
  input: UpdateExerciseRequest,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}`,
    "PATCH",
    input,
  );
}

export function publishExercise(exerciseId: string): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/publish`,
    "POST",
  );
}

export function archiveExercise(exerciseId: string): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/archive`,
    "POST",
  );
}

export function approveExerciseMedia(
  exerciseId: string,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/approve-media`,
    "POST",
  );
}

// IMPL-19: the composed image prompt for one frame, keyed by FrameSpec.key.
// GET /api/admin/exercises/{id}/demo-prompt?key=<frameKey> → { prompt }.
// The backend accepts legacy start/mid/end keys too. Used to seed the
// Regenerate flow's editable prompt when a single frame is targeted.
export async function getDemoPrompt(
  exerciseId: string,
  key: string,
): Promise<string> {
  const data = await apiJson<{ prompt: string }>(
    `/api/admin/exercises/${exerciseId}/demo-prompt?key=${encodeURIComponent(key)}`,
  );
  return data.prompt ?? "";
}

// ── IMPL-19: frame plan ──────────────────────────────────────────────

// Current frame plan + its review status.
export function getPlan(exerciseId: string): Promise<PlanResponse> {
  return apiJson<PlanResponse>(`/api/admin/exercises/${exerciseId}/plan`);
}

// Run the planner (gemini-3.5-flash). Returns the new NEEDS_REVIEW plan.
export function regeneratePlan(
  exerciseId: string,
  promptOverride?: string | null,
): Promise<PlanResponse> {
  return send<PlanResponse>(
    `/api/admin/exercises/${exerciseId}/regenerate-plan`,
    "POST",
    { promptOverride: promptOverride ?? null },
  );
}

// Admin edits to the plan: the full ordered list of frames.
export function savePlan(
  exerciseId: string,
  frames: FrameSpec[],
): Promise<PlanResponse> {
  return send<PlanResponse>(`/api/admin/exercises/${exerciseId}/plan`, "PUT", {
    frames,
  });
}

// Approve the plan: NEEDS_REVIEW → APPROVED.
export function approvePlan(exerciseId: string): Promise<PlanResponse> {
  return send<PlanResponse>(
    `/api/admin/exercises/${exerciseId}/approve-plan`,
    "POST",
  );
}

// ── IMPL-19: media, keyed to the plan ────────────────────────────────

// `key == null` regenerates every frame in the plan (or every legacy phase
// when the exercise has no plan); a specific key regenerates that one frame.
export function regenerateMedia(
  exerciseId: string,
  opts?: { promptOverride?: string | null; key?: string | null },
): Promise<void> {
  return send<void>(`/api/admin/exercises/${exerciseId}/regenerate-media`, "POST", {
    promptOverride: opts?.promptOverride ?? null,
    key: opts?.key ?? null,
  });
}

export async function uploadFrame(
  exerciseId: string,
  key: string,
  file: File,
): Promise<ExerciseResponse> {
  const formData = new FormData();
  formData.append("key", key);
  formData.append("file", file);
  const res = await apiFetch(`/api/admin/exercises/${exerciseId}/upload-frame`, {
    method: "POST",
    body: formData,
  });
  if (!res.ok) {
    throw new BackendError(`upload-frame returned ${res.status}`, res.status);
  }
  return res.json();
}

export function selectFrame(
  exerciseId: string,
  key: string,
  imageUrl: string,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/select-frame`,
    "POST",
    { key, imageUrl },
  );
}

export function deleteFrame(
  exerciseId: string,
  key: string,
  imageUrl: string,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/delete-frame`,
    "POST",
    { key, imageUrl },
  );
}

export function mergeExercise(
  sourceId: string,
  targetId: string,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${sourceId}/merge-into/${targetId}`,
    "POST",
  );
}
