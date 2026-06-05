import { apiJson } from "@/lib/api";

// One row in the dashboard's "Recent" activity feed.
export type LogEntry = {
  icon: string;
  tone: "activity" | "neutral";
  title: string;
  meta?: string;
  metaPhone?: string;
  metaFoldable?: string;
  time: string;
};

// Wire shape of GET /api/me/recent-activity — the backend does the cross-source
// merge/sort/cap; the client just maps `kind` to an icon/tone and formats the
// timestamp. Keep in sync with RecentActivityResponse / ActivityKind.
type ActivityKind = "WORKOUT" | "WEIGH_IN" | "SLEEP" | "FOOD" | "MEDICATION";

type RecentActivity = {
  kind: ActivityKind;
  title: string;
  subtitle: string | null;
  timestamp: string; // ISO instant
};

const MAX_ENTRIES = 5;

// kind → how this row is rendered. The feed is uniform across clients; only the
// icon (Tabler) and tone (green "activity" vs muted "neutral") are web-local.
const STYLE: Record<ActivityKind, { icon: string; tone: LogEntry["tone"] }> = {
  WORKOUT: { icon: "barbell", tone: "activity" },
  WEIGH_IN: { icon: "scale", tone: "neutral" },
  SLEEP: { icon: "moon", tone: "neutral" },
  FOOD: { icon: "salad", tone: "activity" },
  MEDICATION: { icon: "pill", tone: "activity" },
};

/**
 * Load the dashboard "Recent" feed — the user's latest activity across
 * workouts, weigh-ins, sleep, logged food, and medication doses, newest-first.
 * The backend aggregates and caps; this just maps each row for display and
 * degrades to an empty feed on error rather than breaking the page.
 */
export async function loadRecentFeed(): Promise<LogEntry[]> {
  let items: RecentActivity[];
  try {
    items = await apiJson<RecentActivity[]>(`/api/me/recent-activity?limit=${MAX_ENTRIES}`);
  } catch {
    return [];
  }

  return items.map((a) => {
    const style = STYLE[a.kind];
    return {
      icon: style.icon,
      tone: style.tone,
      title: a.title,
      ...(a.subtitle ? { meta: a.subtitle } : {}),
      time: relTime(Date.parse(a.timestamp)),
    };
  });
}

// Compact "time ago" label for the right-hand column: now / 12m / 3h / 2d, or a
// short date once it's older than a week.
function relTime(ts: number, now: number = Date.now()): string {
  const min = Math.floor((now - ts) / 60_000);
  if (min < 1) return "now";
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day}d`;
  return new Date(ts)
    .toLocaleDateString("en-US", { month: "short", day: "numeric" })
    .toUpperCase();
}
