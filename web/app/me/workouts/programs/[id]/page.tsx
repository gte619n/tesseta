import Link from "next/link";
import type { Route } from "next";
import { notFound } from "next/navigation";
import { revalidatePath } from "next/cache";
import { BackendError } from "@/lib/api";
import {
  getProgramDeep,
  getProgramCalendar,
  activateProgram,
  deleteProgram,
} from "@/lib/workout-program-api";
import type { ScheduledWorkoutResponse } from "@/lib/types/workout-program";
import { ProgramRoadmap } from "@/components/workouts/ProgramRoadmap";
import { ProgramThisWeek } from "@/components/workouts/ProgramThisWeek";
import { ProgramDetailActions } from "@/components/workouts/ProgramDetailActions";
import { entityMetadata } from "@/lib/page-metadata";

export const generateMetadata = entityMetadata(
  ({ id }: { id: string }) => getProgramDeep(id),
  (program) => program.title,
);

export const dynamic = "force-dynamic";

// Monday-of-this-week → Sunday, as YYYY-MM-DD, for the "This week" calendar query.
function thisWeekRange(): { from: string; to: string } {
  const now = new Date();
  const day = now.getDay(); // 0 = Sun
  const diffToMonday = (day + 6) % 7;
  const monday = new Date(now);
  monday.setDate(now.getDate() - diffToMonday);
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  return { from: fmt(monday), to: fmt(sunday) };
}

export default async function ProgramDetailPage(props: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await props.params;

  let program;
  try {
    program = await getProgramDeep(id);
  } catch (e) {
    if (e instanceof BackendError && e.status === 404) notFound();
    throw e;
  }

  const detailPath = `/me/workouts/programs/${id}`;

  // "This week" sessions from the calendar endpoint. Best-effort: a DRAFT
  // program has no materialized schedule, so this can be empty.
  let weekSessions: ScheduledWorkoutResponse[] = [];
  try {
    const { from, to } = thisWeekRange();
    weekSessions = await getProgramCalendar(id, from, to);
  } catch {
    weekSessions = [];
  }

  async function activate() {
    "use server";
    await activateProgram(id);
    revalidatePath(detailPath);
  }

  async function archive() {
    "use server";
    await deleteProgram(id);
    revalidatePath("/me/workouts/programs");
    revalidatePath(detailPath);
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[860px] space-y-6">
        <Link
          href={"/me/workouts/programs" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Programs
        </Link>

        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <ProgramStatusBadge status={program.status} />
              {program.goalId ? (
                <Link
                  href={`/me/goals/${program.goalId}` as Route}
                  className="caps-mono rounded-[3px] border-[0.5px] border-accent/40 bg-accent-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-accent-dim hover:opacity-90"
                >
                  Attached to goal: {program.goalTitle ?? "View goal"}
                </Link>
              ) : null}
            </div>
            <h1 className="mt-2 text-[22px] font-medium tracking-[-0.015em] text-primary">
              {program.title}
            </h1>
            {program.description ? (
              <p className="mt-1 max-w-[640px] text-[13px] leading-[1.5] text-secondary">
                {program.description}
              </p>
            ) : null}
          </div>

          <ProgramDetailActions
            programTitle={program.title}
            status={program.status}
            activate={activate}
            archive={archive}
          />
        </header>

        {/* This week */}
        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <h2 className="caps-mono mb-3 text-[10px] tracking-[0.06em] text-tertiary">
            This week
          </h2>
          <ProgramThisWeek sessions={weekSessions} />
        </section>

        {/* Roadmap */}
        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-6">
          <h2 className="caps-mono mb-4 text-[10px] tracking-[0.06em] text-tertiary">
            Roadmap
          </h2>
          {program.phases.length === 0 ? (
            <p className="text-[13px] text-secondary">This program has no phases yet.</p>
          ) : (
            <ProgramRoadmap program={program} />
          )}
        </section>
      </div>
    </main>
  );
}

function ProgramStatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; cls: string }> = {
    DRAFT: { label: "Draft", cls: "bg-canvas-muted text-tertiary" },
    ACTIVE: { label: "Active", cls: "bg-good-bg text-accent-dim" },
    COMPLETED: { label: "Completed", cls: "bg-accent-bg text-accent-dim" },
    ARCHIVED: { label: "Archived", cls: "bg-canvas-muted text-tertiary" },
  };
  const m = map[status] ?? map.DRAFT!;
  return (
    <span className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${m.cls}`}>
      {m.label}
    </span>
  );
}
