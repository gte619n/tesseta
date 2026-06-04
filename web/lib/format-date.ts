/**
 * Format a date-only ISO string (e.g. "2026-06-04") as an uppercased short
 * date — "JUN 4, 2026". Parsed at local midnight (the `T00:00:00` suffix) so
 * the calendar day doesn't shift across time zones. Used by roadmap/program
 * timelines and cards.
 */
export function formatDateUpper(iso: string): string {
  return new Date(`${iso}T00:00:00`)
    .toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" })
    .toUpperCase();
}
