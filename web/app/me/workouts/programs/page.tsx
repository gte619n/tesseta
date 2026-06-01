import Link from "next/link";
import type { Route } from "next";
import { listPrograms } from "@/lib/workout-program-api";
import { ProgramCard } from "@/components/workouts/ProgramCard";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Programs");

export const dynamic = "force-dynamic";

export default async function ProgramsPage() {
  const programs = await listPrograms().catch(() => []);

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href={"/me/workouts" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Workouts
        </Link>

        <header className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Programs
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Periodized training programs: Program → Phase → Workout Day → Block →
              Exercise. Every prescribed exercise is executable at that day&apos;s gym.
            </p>
          </div>
          <Link
            href={"/me/workouts/programs/chat" as Route}
            className="caps-mono inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
          >
            New program
          </Link>
        </header>

        {programs.length === 0 ? (
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-12 text-center">
            <h2 className="m-0 text-[15px] font-medium text-primary">No programs yet</h2>
            <p className="mx-auto mt-2 max-w-[480px] text-[13px] leading-[1.5] text-secondary">
              Chat with the program designer to get an editable, periodized plan
              proposed — phases, deload weeks, and gym-aware exercise selection.
            </p>
            <Link
              href={"/me/workouts/programs/chat" as Route}
              className="caps-mono mt-4 inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
            >
              New program
            </Link>
          </div>
        ) : (
          <div className="space-y-3">
            {programs.map((p) => (
              <Link
                key={p.programId}
                href={`/me/workouts/programs/${p.programId}` as Route}
                className="block transition-opacity hover:opacity-95"
              >
                <ProgramCard program={p} />
              </Link>
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
