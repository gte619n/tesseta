import { apiFetch } from "./api";

// Admin-only nutrition catalog maintenance. Server-only (uses apiFetch, which
// reads server env + the Auth.js session). Do NOT import from client components.

/**
 * Archive duplicate catalog foods (the ones photo/description capture piles up),
 * keeping the best of each name/brand/macro group. Returns how many foods were
 * archived. Idempotent — re-running finds nothing new.
 */
export async function dedupeFoods(): Promise<number> {
  const res = await apiFetch("/api/foods/dedupe", { method: "POST" });
  if (!res.ok) throw new Error(`Food dedupe failed: ${res.status}`);
  const body = (await res.json()) as { archived?: number };
  return body.archived ?? 0;
}
