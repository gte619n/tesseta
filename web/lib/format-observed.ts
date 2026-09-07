// Compact, subtle "when was this observation recorded" label for the dashboard
// metric cards. Day-granular: "today" / "yesterday" / "Nd ago" within a week,
// else a short "Sep 6". Accepts either a YYYY-MM-DD calendar date (daily
// metrics) or a full ISO timestamp (weigh-ins).
export function formatObserved(value: string, now: Date = new Date()): string {
  if (!value) return "";
  const isDateOnly = /^\d{4}-\d{2}-\d{2}$/.test(value);
  const d = isDateOnly ? new Date(value + "T00:00:00") : new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  // Compare at local-midnight granularity so a weigh-in earlier today reads
  // "today", not "5h".
  const midnight = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate());
  const diffDays = Math.round(
    (midnight(now).getTime() - midnight(d).getTime()) / 86_400_000,
  );
  if (diffDays <= 0) return "today";
  if (diffDays === 1) return "yesterday";
  if (diffDays < 7) return `${diffDays}d ago`;
  return d.toLocaleString("en-US", { month: "short", day: "numeric" });
}
