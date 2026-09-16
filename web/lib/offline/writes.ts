// Client-side, offline-safe versions of the three highest-traffic writes,
// routed through the mutation outbox instead of awaiting a server action. Each
// returns as soon as the mutation is durably journaled (the caller has already
// applied its optimistic UI); the background drain replays it with the client
// UUID as the Idempotency-Key, so the write survives a flaky network or a tab
// close and the backend dedupes a replay (the write contract).
//
// These mirror the paths in lib/offline/replay-endpoints.ts (the proxy
// allowlist) and the server-side helpers in lib/nutrition-api.ts etc.

import { submitMutation } from "./mutation-client";

/** Add a nutrition entry. `body` must include a client-minted `id` (also the
 *  optimistic row's id) so the optimistic row and the server row share identity
 *  (no flicker on reconcile) and a replay dedupes. The body shape is the same
 *  AddEntryBody the server action sends, plus `id`. */
export function addEntryOffline(date: string, body: unknown): Promise<string> {
  return submitMutation({
    kind: "nutrition.addEntry",
    endpoint: "nutrition.entries.create",
    method: "POST",
    path: `/api/me/nutrition/${date}/entries`,
    body,
  });
}

export function updateEntryOffline(
  date: string,
  entryId: string,
  body: Record<string, unknown>,
): Promise<string> {
  return submitMutation({
    kind: "nutrition.updateEntry",
    endpoint: "nutrition.entries.update",
    method: "PATCH",
    path: `/api/me/nutrition/${date}/entries/${entryId}`,
    body,
  });
}

export function deleteEntryOffline(date: string, entryId: string): Promise<string> {
  return submitMutation({
    kind: "nutrition.deleteEntry",
    endpoint: "nutrition.entries.delete",
    method: "DELETE",
    path: `/api/me/nutrition/${date}/entries/${entryId}`,
  });
}

/** Log a medication dose (idempotent per (med, date, window) on the server). */
export function logDoseOffline(medicationId: string, window: string): Promise<string> {
  return submitMutation({
    kind: "medication.logDose",
    endpoint: "medications.adherence.log",
    method: "POST",
    path: `/api/me/medications/${medicationId}/adherence`,
    body: { window },
  });
}

/** Complete/skip a workout session (deterministic id, set-semantics PUT). */
export function logSessionOffline(
  programId: string,
  scheduledId: string,
  input: unknown,
): Promise<string> {
  return submitMutation({
    kind: "workout.logSession",
    endpoint: "workoutPrograms.sessions.log",
    method: "PUT",
    path: `/api/me/workout-programs/${programId}/sessions/${scheduledId}`,
    body: input,
  });
}
