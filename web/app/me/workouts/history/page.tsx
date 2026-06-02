import Link from "next/link";
import type { Route } from "next";
import { getWorkoutHistory } from "@/lib/workout-program-api";
import type { ScheduledWorkoutResponse } from "@/lib/types/workout-program";
import { WorkoutHistoryList } from "@/components/workouts/WorkoutHistoryList";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Workout History");

export const dynamic = "force-dynamic";

export default async function WorkoutHistoryPage() {
  let sessions: ScheduledWorkoutResponse[] = [];
  try {
    sessions = await getWorkoutHistory();
  } catch {
    sessions = [];
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[860px] space-y-6">
        <Link
          href={"/me/workouts" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Workouts
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Workout History
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Every workout you&apos;ve completed, newest first — when you did it,
            how long it took, and the sets you logged.
          </p>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <WorkoutHistoryList sessions={sessions} />
        </section>
      </div>
    </main>
  );
}
