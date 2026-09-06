import { apiJson, send } from "./api";
import type {
  PatternReview,
  BlockParameters,
  UpdateBlockParametersRequest,
  ExerciseStrength,
} from "./types/progression";
import type { EnergyBalance } from "./types/plan";

// Server-only HTTP helpers for the progression console (IMPL-PROG-01). Do not
// import from client components — apiFetch reads server env + the Auth.js
// session. Mirrors the Android ProgressionApi contract exactly. All three
// endpoints are user-scoped with no path params (the backend derives the user
// from the auth token).

// ── Reads ────────────────────────────────────────────────────────────

// Per-movement-pattern weekly analysis (trend, fatigue, proposed set target).
export function getWeekReview(): Promise<PatternReview[]> {
  return apiJson<PatternReview[]>("/api/me/progression/week-review");
}

// The active training block's parameters (mode, rep ranges, RIR caps, …).
export function getBlockParameters(): Promise<BlockParameters> {
  return apiJson<BlockParameters>("/api/me/progression/block-parameters");
}

// Per-exercise estimated 1RM (the "weights"), heaviest-first.
export function getStrength(): Promise<ExerciseStrength[]> {
  return apiJson<ExerciseStrength[]>("/api/me/progression/strength");
}

// The engine's measured energy state (maintenance TDEE, mean intake, balance).
// Used by the plan-coherence view. May be absent on older backends — callers
// should tolerate a throw (the coherence page treats it as "no engine data").
export function getEnergyBalance(): Promise<EnergyBalance> {
  return apiJson<EnergyBalance>("/api/me/progression/energy-balance");
}

// ── Mutations ────────────────────────────────────────────────────────

// Partial update of the block parameters; returns the updated block. Currently
// the console only edits `mode` (pinning it sets manualOverride server-side).
export function updateBlockParameters(
  body: UpdateBlockParametersRequest,
): Promise<BlockParameters> {
  return send<BlockParameters>(
    "/api/me/progression/block-parameters",
    "PUT",
    body,
  );
}
