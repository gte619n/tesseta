import type { WorkoutProgramResponse, ProgramStatus } from "@/lib/types/workout-program";
import { WEEK_DAY_LABEL } from "@/lib/types/workout-program";
import { formatDateUpper } from "@/lib/format-date";

const STATUS_PILL: Record<ProgramStatus, { label: string; cls: string }> = {
  DRAFT: { label: "Draft", cls: "bg-canvas-muted text-tertiary" },
  ACTIVE: { label: "Active", cls: "bg-good-bg text-accent-dim" },
  COMPLETED: { label: "Completed", cls: "bg-accent-bg text-accent-dim" },
  ARCHIVED: { label: "Archived", cls: "bg-canvas-muted text-tertiary" },
};

export function ProgramCard({ program }: { program: WorkoutProgramResponse }) {
  const pill = STATUS_PILL[program.status];
  const trainingDays = program.trainingDays
    .map((d) => WEEK_DAY_LABEL[d])
    .join(" · ");

  // Best-effort "Week N of M": completed phases are full weeks; without a
  // current-week field on the shallow response we approximate progress from
  // completed phase count when active.
  const currentWeek =
    program.status === "ACTIVE" && program.totalWeeks > 0
      ? Math.min(program.totalWeeks, Math.max(1, program.completedPhaseCount + 1))
      : null;

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${pill.cls}`}>
              {pill.label}
            </span>
            <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
              {program.source === "MANUAL" ? "Manual" : program.source === "AI_GENERATED" ? "AI" : "AI-assisted"}
            </span>
          </div>
          <h3 className="mt-2 text-[16px] font-medium leading-[1.25] tracking-[-0.01em] text-primary">
            {program.title}
          </h3>
          {program.description ? (
            <p className="mt-1 line-clamp-2 text-[13px] text-secondary">{program.description}</p>
          ) : null}
        </div>
        <div className="shrink-0 text-right">
          <div className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">Starts</div>
          <div className="caps-mono mt-px text-[11px] tracking-[0.04em] text-secondary">
            {formatDateUpper(program.startDate)}
          </div>
        </div>
      </div>

      <div className="mt-4 flex items-center gap-3">
        <PhaseSpine
          phaseCount={program.phaseCount}
          completedPhaseCount={program.completedPhaseCount}
          status={program.status}
        />
        {currentWeek != null ? (
          <div className="caps-mono shrink-0 text-[10px] tracking-[0.06em] text-tertiary">
            Week {currentWeek} of {program.totalWeeks}
          </div>
        ) : (
          <div className="caps-mono shrink-0 text-[10px] tracking-[0.06em] text-tertiary">
            {program.totalWeeks} week{program.totalWeeks === 1 ? "" : "s"}
          </div>
        )}
      </div>

      {trainingDays ? (
        <div className="caps-mono mt-2 text-[10px] tracking-[0.06em] text-tertiary">
          {trainingDays}
        </div>
      ) : null}
    </div>
  );
}

// A compact phase spine: one segment per phase, colored by completed / active /
// locked. Deload weeks are marked separately on the detail roadmap.
function PhaseSpine({
  phaseCount,
  completedPhaseCount,
  status,
}: {
  phaseCount: number;
  completedPhaseCount: number;
  status: ProgramStatus;
}) {
  if (phaseCount === 0) {
    return <div className="h-1.5 flex-1 rounded-full bg-canvas-muted" aria-hidden />;
  }
  const segments = Array.from({ length: phaseCount });
  const activeIndex = status === "ACTIVE" ? completedPhaseCount : -1;
  return (
    <div className="flex flex-1 items-center gap-[3px]" aria-hidden>
      {segments.map((_, i) => {
        const done = i < completedPhaseCount;
        const active = i === activeIndex;
        const cls = done
          ? "bg-accent"
          : active
            ? "bg-accent/60"
            : "bg-canvas-muted";
        return <div key={i} className={`h-1.5 flex-1 rounded-full ${cls}`} />;
      })}
    </div>
  );
}
