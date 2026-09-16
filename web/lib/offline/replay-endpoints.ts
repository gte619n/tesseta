// Allowlist of backend writes the outbox replay proxy may forward. The proxy
// (app/api/outbox/replay/route.ts) attaches the user's bearer token, so it must
// NOT forward arbitrary client-supplied paths — that would let a page replay to
// any backend endpoint (including admin) under the user's identity. Each entry
// pins an HTTP method + a path matcher; a replay whose (method, path) matches no
// entry is rejected 400 before any backend call.
//
// These are exactly the user-data mutating endpoints classified replay-safe in
// the backend write contract (docs/reference/write-contract.md). Keep the two in
// sync: only add an endpoint here once it is KEY_GUARDED / DETERMINISTIC_ID /
// SET_SEMANTICS / IDEMPOTENT_DELETE there.

export interface ReplayEndpoint {
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  /** Anchored regex for the path (path variables as segments). */
  pattern: RegExp;
}

const DATE = "\\d{4}-\\d{2}-\\d{2}";
const SEG = "[^/]+"; // one non-empty, non-slash path segment (an id)

export const REPLAY_ENDPOINTS: ReplayEndpoint[] = [
  // Nutrition entries (the highest-traffic web write).
  { method: "POST", pattern: new RegExp(`^/api/me/nutrition/${DATE}/entries$`) },
  { method: "PATCH", pattern: new RegExp(`^/api/me/nutrition/${DATE}/entries/${SEG}$`) },
  { method: "DELETE", pattern: new RegExp(`^/api/me/nutrition/${DATE}/entries/${SEG}$`) },
  // Medication adherence.
  { method: "POST", pattern: new RegExp(`^/api/me/medications/${SEG}/adherence$`) },
  {
    method: "DELETE",
    pattern: new RegExp(`^/api/me/medications/${SEG}/adherence/${DATE}/${SEG}$`),
  },
  // Workout session logging (deterministic id, set-semantics PUT).
  { method: "PUT", pattern: new RegExp(`^/api/me/workout-programs/${SEG}/sessions/${SEG}$`) },
];

/** True when (method, path) matches an allowlisted replayable endpoint. */
export function isReplayable(method: string, path: string): boolean {
  return REPLAY_ENDPOINTS.some((e) => e.method === method && e.pattern.test(path));
}
