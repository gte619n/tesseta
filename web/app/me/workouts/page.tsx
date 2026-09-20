import { listPrograms, getProgramDeep, getProgramCalendar, getWorkoutHistory } from "@/lib/workout-program-api";
import { getWorkoutStats } from "@/lib/workout-stats-api";
import { buildCurrentProgramView } from "@/lib/workout-overview";
import { addDays } from "@/lib/workout-stats-format";
import { StreakHero } from "@/components/workouts/StreakHero";
import { ConsistencyHeatmap } from "@/components/workouts/ConsistencyHeatmap";
import { CurrentProgramCard, type CurrentProgramView } from "@/components/workouts/CurrentProgramCard";
import { LatestWorkouts } from "@/components/workouts/LatestWorkouts";
import { StrengthTrendChart } from "@/components/workouts/StrengthTrendChart";
import { WeeklyVolumeChart } from "@/components/workouts/WeeklyVolumeChart";
import { PatternBalanceCard } from "@/components/workouts/PatternBalanceCard";
import { RecentPrsCard } from "@/components/workouts/RecentPrsCard";
import { getWeekReview } from "@/lib/progression-api";
import { getE1rmHistory } from "@/lib/workout-stats-api";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Workouts");

export const dynamic = "force-dynamic";

// The workout Overview dashboard (IMPL-WEB-WORKOUT-01): streak + consistency
// hero, current program, latest workouts, and the progress charts. A thin
// server component — it only fetches and hands plain props to the presentational
// cards, each of which degrades to a friendly empty state (D12) so the page
// never blanks, even for a brand-new user or when the stats endpoint is absent.

function todayIso(): string {
  // Server clock is UTC in prod; the backend already resolves the user's local
  // week via X-Timezone, and the heatmap only needs day-grain, so UTC today is
  // fine for the client-side grid framing.
  return new Date().toISOString().slice(0, 10);
}

export default async function WorkoutsOverviewPage() {
  const today = todayIso();

  const [stats, programs, history] = await Promise.all([
    getWorkoutStats(26).catch(() => null),
    listPrograms().catch(() => []),
    getWorkoutHistory(0, 5).catch(() => null),
  ]);

  const activeProgram = programs.find((p) => p.status === "ACTIVE") ?? null;

  // Build the current-program view (deep + a 3-week calendar window for the next
  // session). Best-effort: any failure degrades the card to its empty CTA.
  let currentProgram: CurrentProgramView | null = null;
  if (activeProgram) {
    const [deep, calendar] = await Promise.all([
      getProgramDeep(activeProgram.programId).catch(() => null),
      getProgramCalendar(activeProgram.programId, today, addDays(today, 21)).catch(() => []),
    ]);
    if (deep) {
      currentProgram = buildCurrentProgramView(deep, calendar, today);
    }
  }

  // Strength chart: default to a *major* lift that actually has trend data — the
  // first main-pattern default the user has logged on ≥2 sessions (so the chart
  // opens on a real trend, not an empty one) — falling back to the most-recent
  // tracked lift, then any default. Its history is fetched server-side for first
  // paint; the picker fetches the rest client-side via the route handler.
  const tracked = stats?.trackedExercises ?? [];
  const trackedIds = new Set(tracked.map((e) => e.exerciseId));
  const defaultLift =
    stats?.chartDefaultLifts?.find((l) => trackedIds.has(l.exerciseId)) ??
    tracked[0] ??
    stats?.chartDefaultLifts?.[0] ??
    null;
  const [initialHistory, weekReview] = await Promise.all([
    defaultLift ? getE1rmHistory(defaultLift.exerciseId).catch(() => null) : Promise.resolve(null),
    getWeekReview().catch(() => []),
  ]);

  const latestSessions = history?.items ?? [];

  return (
    <main className="bg-canvas px-8 pb-16 pt-6">
      <div className="mx-auto max-w-[1040px]">
        {/* Cards brick-pack (CSS masonry) so they flow into gaps instead of
            aligning into rows with dead whitespace. `break-inside-avoid` keeps a
            card whole; `mb-4` is the vertical gutter. */}
        <div className="columns-1 gap-4 lg:columns-2">
          <div className="mb-4 break-inside-avoid">
            <StreakHero streak={stats?.streak ?? null} />
          </div>
          <div className="mb-4 break-inside-avoid">
            <ConsistencyHeatmap days={stats?.heatmap ?? []} today={today} />
          </div>
          <div className="mb-4 break-inside-avoid">
            <CurrentProgramCard program={currentProgram} />
          </div>
          <div className="mb-4 break-inside-avoid">
            <LatestWorkouts sessions={latestSessions} />
          </div>
          <div className="mb-4 break-inside-avoid">
            <StrengthTrendChart
              lifts={stats?.trackedExercises ?? []}
              defaultLifts={stats?.chartDefaultLifts ?? []}
              initialHistory={initialHistory}
            />
          </div>
          <div className="mb-4 break-inside-avoid">
            <WeeklyVolumeChart
              series={stats?.weeklySeries ?? []}
              weeklyTarget={stats?.streak?.weeklyTarget ?? 0}
            />
          </div>
          <div className="mb-4 break-inside-avoid">
            <PatternBalanceCard review={weekReview} />
          </div>
          <div className="mb-4 break-inside-avoid">
            <RecentPrsCard prs={stats?.recentPrs ?? []} />
          </div>
        </div>
      </div>
    </main>
  );
}
