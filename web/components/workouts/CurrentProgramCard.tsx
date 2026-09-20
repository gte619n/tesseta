import Link from "next/link";
import type { Route } from "next";
import type { WeekDay } from "@/lib/types/workout-program";
import { WEEK_DAY_LABEL } from "@/lib/types/workout-program";
import { formatDateUpper } from "@/lib/format-date";

// The active program at a glance (IMPL-WEB-WORKOUT-01 §4): title, phase
// progress, training days, and the next scheduled session — or, with no active
// program, a "Design a program" CTA into the chat designer (D12). A plain
// view-model so the page owns the fetching and this stays testable.

export type CurrentProgramView = {
  programId: string;
  title: string;
  trainingDays: WeekDay[];
  phaseTitle: string | null;
  phaseNumber: number | null; // 1-based
  phaseCount: number | null;
  weekInPhase: number | null;
  weeksInPhase: number | null;
  nextSession: {
    programId: string;
    scheduledId: string;
    date: string;
    dayLabel: string;
  } | null;
};

export function CurrentProgramCard({
  program,
}: {
  program: CurrentProgramView | null;
}) {
  if (!program) {
    return (
      <div className="flex flex-col rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Current program
        </div>
        <p className="mt-3 text-[13px] text-secondary">
          No active program. Design one and the AI coach drives your weights and
          reps every session.
        </p>
        <Link
          href="/me/workouts/programs/chat"
          className="mt-4 inline-flex w-fit items-center gap-1.5 rounded-[8px] bg-accent px-3 py-1.5 text-[13px] font-medium text-white transition-colors hover:bg-accent-dim"
          data-testid="design-program-cta"
        >
          Design a program
        </Link>
      </div>
    );
  }

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-center justify-between">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Current program
        </div>
        <span className="caps-mono rounded-full bg-accent/15 px-2 py-0.5 text-[9px] tracking-[0.06em] text-accent-dim">
          Active
        </span>
      </div>

      <Link
        href={`/me/workouts/programs/${program.programId}` as Route}
        className="mt-2 block text-[16px] font-medium text-primary hover:text-accent-dim"
      >
        {program.title}
      </Link>

      {(program.phaseTitle || program.phaseNumber) && (
        <div className="mt-2 text-[13px] text-secondary">
          {program.phaseNumber && program.phaseCount
            ? `Phase ${program.phaseNumber} of ${program.phaseCount}`
            : "Phase"}
          {program.phaseTitle && (
            <span className="text-tertiary"> · {program.phaseTitle}</span>
          )}
          {program.weekInPhase && program.weeksInPhase && (
            <span className="text-tertiary">
              {" "}
              · Week {program.weekInPhase}/{program.weeksInPhase}
            </span>
          )}
        </div>
      )}

      {program.trainingDays.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {program.trainingDays.map((d) => (
            <span
              key={d}
              className="rounded-[6px] bg-canvas px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.04em] text-tertiary"
            >
              {WEEK_DAY_LABEL[d]}
            </span>
          ))}
        </div>
      )}

      <div className="mt-4 border-t border-border-subtle pt-3">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Next session
        </div>
        {program.nextSession ? (
          <Link
            href={`/me/workouts/programs/${program.programId}` as Route}
            className="mt-1 block text-[13px] text-primary hover:text-accent-dim"
          >
            {program.nextSession.dayLabel}
            <span className="ml-2 font-mono text-[11px] text-tertiary tabular-nums">
              {formatDateUpper(program.nextSession.date)}
            </span>
          </Link>
        ) : (
          <p className="mt-1 text-[13px] text-secondary">
            Nothing scheduled ahead.
          </p>
        )}
      </div>
    </div>
  );
}
