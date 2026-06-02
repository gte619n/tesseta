import { apiFetch, apiJson, BackendError } from "./api";
import type {
  ExerciseResponse,
  CreateExerciseRequest,
  UpdateExerciseRequest,
  DemoPhase,
} from "./types/exercise";
import type { Equipment } from "./types/gym";

// Server-only HTTP helpers for the Exercise catalog (IMPL-14). Do not import
// from client components — apiFetch reads server env + the Auth.js session.
// Mirrors gym-api.ts / drug-admin-api.ts conventions.

// ── Internal request helper ──────────────────────────────────────────

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

export async function getDemoPrompt(
  exerciseId: string,
  phase: DemoPhase,
): Promise<string> {
  const data = await apiJson<{ prompt: string }>(
    `/api/admin/exercises/${exerciseId}/demo-prompt?phase=${phase}`,
  );
  return data.prompt ?? "";
}

export function regenerateMedia(
  exerciseId: string,
  opts?: { promptOverride?: string | null; phase?: DemoPhase | null },
): Promise<void> {
  return send<void>(`/api/admin/exercises/${exerciseId}/regenerate-media`, "POST", {
    promptOverride: opts?.promptOverride ?? null,
    phase: opts?.phase ?? null,
  });
}

export async function uploadFrame(
  exerciseId: string,
  phase: DemoPhase,
  file: File,
): Promise<ExerciseResponse> {
  const formData = new FormData();
  formData.append("phase", phase);
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
  phase: DemoPhase,
  imageUrl: string,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/select-frame`,
    "POST",
    { phase, imageUrl },
  );
}

export function deleteFrame(
  exerciseId: string,
  phase: DemoPhase,
  imageUrl: string,
): Promise<ExerciseResponse> {
  return send<ExerciseResponse>(
    `/api/admin/exercises/${exerciseId}/delete-frame`,
    "POST",
    { phase, imageUrl },
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
