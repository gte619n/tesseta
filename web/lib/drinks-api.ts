import { apiJson, send } from "./api";
import type { Drink, DrinkProposal, SaveDrinkBody } from "./types/nutrition";

// Server-only HTTP helpers for the drink catalog (IMPL-DRINK-01, web = setup).
// Same conventions as lib/nutrition-api.ts: call from server components /
// server actions only (apiFetch reads server env + the Auth.js session). The
// backend contract lives at /api/me/drinks (see DrinkController).

// ── Reads ────────────────────────────────────────────────────────────

/** List my non-archived drinks (category="drink", createdBy == me), newest first. */
export function listDrinks(): Promise<Drink[]> {
  return apiJson<Drink[]>("/api/me/drinks");
}

// ── Mutations ────────────────────────────────────────────────────────

/**
 * AI proposal for a drink name (nothing persisted). Returns the proposal to
 * review/edit before saving. On analyzer failure the backend responds 422 —
 * `send` throws a `BackendError` with `status === 422`, which the caller
 * catches to fall back to fully-manual entry.
 */
export function analyzeDrink(name: string): Promise<DrinkProposal> {
  return send<DrinkProposal>("/api/me/drinks/analyze", "POST", { name });
}

/** Persist a new drink (201) + enqueue its beverage image (imageStatus=PENDING). */
export function createDrink(body: SaveDrinkBody): Promise<Drink> {
  return send<Drink>("/api/me/drinks", "POST", body);
}

/** Edit a drink (re-derives the alcohol math server-side). */
export function updateDrink(foodId: string, body: SaveDrinkBody): Promise<Drink> {
  return send<Drink>(`/api/me/drinks/${foodId}`, "PUT", body);
}

/**
 * Re-enqueue image generation (202). Also the recovery path for a drink whose
 * imageStatus is FAILED; the page's PendingImageRefresher then polls to READY.
 */
export function regenerateDrinkImage(foodId: string): Promise<Drink> {
  return send<Drink>(`/api/me/drinks/${foodId}/image/regenerate`, "POST");
}

/** Archive (soft-delete) a drink — 204. Historical entries keep their snapshot. */
export function archiveDrink(foodId: string): Promise<void> {
  return send<void>(`/api/me/drinks/${foodId}`, "DELETE");
}
