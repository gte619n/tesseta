import { cookies } from "next/headers";

// XPLAT-001 — one canonical "today". The user's IANA timezone (recorded in the
// `tz` cookie by <TimezoneCookie/>, the same value apiFetch forwards as
// X-Timezone) is the single source of truth for what "today" is. These helpers
// derive the local calendar date in that zone instead of the server's UTC clock
// (`new Date().toISOString()`), so an evening log in a negative-UTC-offset zone
// lands on the correct local day rather than tomorrow.

// Format a Date as yyyy-MM-dd as it reads on the wall clock in `tz`. Uses
// Intl with en-CA (which renders ISO-ordered y-m-d) so we avoid manual offset
// math and honour DST. Falls back to UTC if the zone is invalid.
function localDateInZone(now: Date, tz: string | undefined): string {
  try {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: tz || "UTC",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(now);
  } catch {
    // Invalid/unknown zone string — degrade to UTC rather than throw.
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: "UTC",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(now);
  }
}

// Read the browser's zone from the `tz` cookie (server-request scope only).
// Returns undefined outside a request scope (prerender/unit tests) or when the
// cookie hasn't been set yet — callers then get UTC, matching the backend's
// missing-header fallback.
async function tzFromCookie(): Promise<string | undefined> {
  try {
    const raw = (await cookies()).get("tz")?.value;
    return raw ? decodeURIComponent(raw) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The user's local calendar date today (yyyy-MM-dd) in their timezone. Server
 * components call this instead of `new Date().toISOString().split("T")[0]`.
 */
export async function todayInUserZone(): Promise<string> {
  return localDateInZone(new Date(), await tzFromCookie());
}

/**
 * `n` days offset from the user's local today (yyyy-MM-dd), in their timezone.
 * Negative `n` = past. Anchors on the local date so the range boundaries line
 * up with what the user sees, not UTC midnight.
 */
export async function daysAgoInUserZone(n: number): Promise<string> {
  const tz = await tzFromCookie();
  const todayStr = localDateInZone(new Date(), tz);
  // Shift by whole days on the local date anchor (noon-UTC keeps us clear of
  // DST edges), then re-read — the shift is calendar-day arithmetic so the zone
  // no longer matters for the offset itself.
  const d = new Date(`${todayStr}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().split("T")[0] ?? todayStr;
}
